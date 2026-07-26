package com.kvita.diskmapper.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kvita.diskmapper.data.AppStorageStats
import com.kvita.diskmapper.data.AndroidPrivateAccounting
import com.kvita.diskmapper.data.ByteTotals
import com.kvita.diskmapper.data.PathBytes
import com.kvita.diskmapper.data.StorageItem
import com.kvita.diskmapper.data.StorageScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class ScanSource {
    SAF,
    ALL_FILES,
    SHIZUKU_ANDROID,
    APP_STATS
}

data class DiskMapperUiState(
    val selectedFolderUri: Uri? = null,
    val selectedRootPath: String? = null,
    val scanSource: ScanSource = ScanSource.SAF,
    val isScanning: Boolean = false,
    val visitedNodes: Long = 0,
    val rootLogicalSizeBytes: Long = 0,
    val rootOnDiskSizeBytes: Long = 0,
    val shizukuTelegramOnly: Boolean = false,
    val shizukuDiagnostics: String? = null,
    val items: List<StorageItem> = emptyList(),
    /** Identifies the scan that produced [items]; null while nothing is loaded. */
    val loadedKey: String? = null,
    val errorMessage: String? = null
)

class DiskMapperViewModel : ViewModel() {
    private val scanner = StorageScanner()
    private val shizukuBridge = ShizukuBridge()

    private fun DiskMapperUiState.scanKey(): String =
        "$scanSource|${selectedRootPath ?: selectedFolderUri}|$shizukuTelegramOnly"

    private val scanGeneration = java.util.concurrent.atomic.AtomicLong(0)

    /** Claims the newest scan slot; earlier scans become stale from here on. */
    private fun beginScan(): Long = scanGeneration.incrementAndGet()

    /**
     * Applies [transform] only if [generation] is still the newest scan, so a
     * slow scan cannot overwrite a newer one's results or stop its spinner.
     */
    private fun updateIfCurrent(
        generation: Long,
        transform: (DiskMapperUiState) -> DiskMapperUiState
    ) {
        if (scanGeneration.get() != generation) return
        _uiState.update { transform(it) }
    }

    /**
     * Drops results that belong to a different scan target, so a new source
     * never shows the previous source's tree — including when it fails.
     */
    private fun DiskMapperUiState.startingScan(): DiskMapperUiState {
        val stale = loadedKey != null && loadedKey != scanKey()
        return copy(
            isScanning = true,
            visitedNodes = 0,
            errorMessage = null,
            items = if (stale) emptyList() else items,
            loadedKey = if (stale) null else loadedKey,
            rootLogicalSizeBytes = if (stale) 0 else rootLogicalSizeBytes,
            rootOnDiskSizeBytes = if (stale) 0 else rootOnDiskSizeBytes,
            shizukuDiagnostics = if (stale) null else shizukuDiagnostics
        )
    }

    private val _uiState = MutableStateFlow(DiskMapperUiState())
    val uiState: StateFlow<DiskMapperUiState> = _uiState.asStateFlow()

    override fun onCleared() {
        shizukuBridge.close()
        super.onCleared()
    }

    fun hasUsageAccess(context: Context): Boolean =
        AppStorageStats.hasUsageAccess(context.applicationContext)

    fun hasShizukuAccess(): Boolean = shizukuBridge.canUseWithoutRequest()

    fun restorePersistedFolder(context: Context) {
        if (_uiState.value.selectedFolderUri != null || _uiState.value.selectedRootPath != null) return
        val persisted = context.contentResolver.persistedUriPermissions.firstOrNull()?.uri ?: return
        UiTrace.vm("restorePersistedFolder uri=$persisted")
        _uiState.update { it.copy(selectedFolderUri = persisted, scanSource = ScanSource.SAF) }
        scan(context)
    }

    fun selectFolder(context: Context, uri: Uri) {
        UiTrace.vm("selectFolder uri=$uri")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
            UiTrace.vm("takePersistableUriPermission failed for uri=$uri")
        }

        _uiState.update {
            it.copy(
                selectedFolderUri = uri,
                selectedRootPath = null,
                scanSource = ScanSource.SAF,
                errorMessage = null
            )
        }
        scan(context)
    }

    fun selectAllFilesRoot(path: String, context: Context) {
        UiTrace.vm("selectAllFilesRoot path=$path")
        val warning = if (path == sharedStorageRoot()) {
            when (shizukuBridge.ensurePermission()) {
                ShizukuBridge.PermissionState.READY -> null
                ShizukuBridge.PermissionState.PERMISSION_REQUESTED ->
                    "Shizuku permission requested. Approve it for full Android/data merge, then run Root scan again."
                ShizukuBridge.PermissionState.PERMISSION_DENIED ->
                    "Shizuku permission denied. Root scan may show limited Android/data."
                ShizukuBridge.PermissionState.SHIZUKU_NOT_RUNNING ->
                    "Shizuku is not running. Root scan may show limited Android/data."
            }
        } else {
            null
        }

        _uiState.update {
            it.copy(
                selectedFolderUri = null,
                selectedRootPath = path,
                scanSource = ScanSource.ALL_FILES,
                errorMessage = warning
            )
        }
        scan(context)
    }

    fun scanAndroidPrivateWithShizuku(context: Context, telegramOnly: Boolean): ShizukuBridge.PermissionState {
        UiTrace.vm("scanAndroidPrivateWithShizuku telegramOnly=$telegramOnly")
        return when (val state = shizukuBridge.ensurePermission()) {
            ShizukuBridge.PermissionState.SHIZUKU_NOT_RUNNING -> {
                UiTrace.vm("shizuku state=NOT_RUNNING")
                _uiState.update {
                    it.copy(errorMessage = "Shizuku is not running. Start Shizuku first.")
                }
                state
            }
            ShizukuBridge.PermissionState.PERMISSION_REQUESTED -> {
                UiTrace.vm("shizuku state=PERMISSION_REQUESTED")
                _uiState.update {
                    it.copy(errorMessage = "Shizuku permission requested. Confirm and tap again.")
                }
                state
            }
            ShizukuBridge.PermissionState.PERMISSION_DENIED -> {
                UiTrace.vm("shizuku state=PERMISSION_DENIED")
                _uiState.update {
                    it.copy(errorMessage = "Shizuku permission denied.")
                }
                state
            }
            ShizukuBridge.PermissionState.READY -> {
                UiTrace.vm("shizuku state=READY")
                _uiState.update {
                    it.copy(
                        selectedFolderUri = null,
                        selectedRootPath = "${sharedStorageRoot()}/Android",
                        scanSource = ScanSource.SHIZUKU_ANDROID,
                        shizukuTelegramOnly = telegramOnly,
                        shizukuDiagnostics = null,
                        errorMessage = null
                    )
                }
                scanShizuku(context, telegramOnly)
                state
            }
        }
    }

    fun scan(context: Context) {
        val state = _uiState.value
        val scanKey = state.scanKey()
        val generation = beginScan()
        UiTrace.vm("scan start source=${state.scanSource} folder=${state.selectedFolderUri} root=${state.selectedRootPath}")
        // Synchronous with beginScan(): if this ran inside the coroutine, a
        // superseded scan could raise the spinner after the newer one finished
        // and never be allowed to lower it again.
        _uiState.update { it.startingScan() }

        viewModelScope.launch {

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (state.scanSource) {
                        ScanSource.SAF -> {
                            val rootUri = state.selectedFolderUri
                                ?: throw IllegalStateException("Folder is not selected")
                            scanner.scan(context.applicationContext, rootUri) { visited ->
                                updateIfCurrent(generation) { it.copy(visitedNodes = visited) }
                            }
                        }
                        ScanSource.ALL_FILES -> {
                            val rootPath = state.selectedRootPath
                                ?: throw IllegalStateException("Root path is not selected")
                            val baseScan = scanner.scanFileTree(File(rootPath)) { visited ->
                                updateIfCurrent(generation) { it.copy(visitedNodes = visited) }
                            }
                            if (rootPath == sharedStorageRoot() && shizukuBridge.canUseWithoutRequest()) {
                                try {
                                    val payload = shizukuBridge.scanAndroidPrivate(context.applicationContext, false)
                                    val shizukuItems = parseShizukuPayload(payload)
                                    mergeRootAndShizuku(baseScan, shizukuItems)
                                } catch (e: Throwable) {
                                    UiTrace.error("shizuku merge failed, using base scan only", e)
                                    baseScan
                                }
                            } else {
                                baseScan
                            }
                        }
                        ScanSource.SHIZUKU_ANDROID -> {
                            throw IllegalStateException(
                                "Use Shizuku scan action for Android/data and Android/obb."
                            )
                        }
                        ScanSource.APP_STATS -> {
                            throw IllegalStateException(
                                "Use Apps action for per-app storage stats."
                            )
                        }
                    }
                }
            }

            result.onSuccess { scanResult ->
                UiTrace.vm(
                    "scan success source=${state.scanSource} items=${scanResult.items.size} visited=${scanResult.visitedNodes} rootOnDisk=${scanResult.rootOnDiskSizeBytes} rootLogical=${scanResult.rootLogicalSizeBytes}"
                )
                updateIfCurrent(generation) {
                    it.copy(
                    isScanning = false,
                    visitedNodes = scanResult.visitedNodes,
                    rootLogicalSizeBytes = scanResult.rootLogicalSizeBytes,
                    rootOnDiskSizeBytes = scanResult.rootOnDiskSizeBytes,
                    items = scanResult.items,
                    loadedKey = scanKey
                )
                }
            }.onFailure { throwable ->
                UiTrace.error("scan failed source=${state.scanSource}", throwable)
                updateIfCurrent(generation) {
                    it.copy(
                    isScanning = false,
                    errorMessage = throwable.message ?: "Scan failed"
                )
                }
            }
        }
    }

    private fun scanShizuku(context: Context, telegramOnly: Boolean) {
        val scanKey = _uiState.value.scanKey()
        val generation = beginScan()
        UiTrace.vm("scanShizuku start telegramOnly=$telegramOnly")
        _uiState.update { it.startingScan() }
        viewModelScope.launch {

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val diagnostics = shizukuBridge.diagnostics(context.applicationContext)
                    val payload = shizukuBridge.scanAndroidPrivate(context.applicationContext, telegramOnly)
                    Pair(diagnostics, parseShizukuPayload(payload))
                }
            }

            result.onSuccess { (diagnostics, items) ->
                val totals = AndroidPrivateAccounting.rootTotals(items.map { it.toPathBytes() })
                val accessWarning = buildShizukuAccessWarning(diagnostics, items)
                UiTrace.vm(
                    "scanShizuku success items=${items.size} onDisk=${totals.onDiskBytes} logical=${totals.logicalBytes} diagnostics=$diagnostics warning=${!accessWarning.isNullOrBlank()}"
                )
                updateIfCurrent(generation) {
                    it.copy(
                        isScanning = false,
                        visitedNodes = items.size.toLong(),
                        rootLogicalSizeBytes = totals.logicalBytes,
                        rootOnDiskSizeBytes = totals.onDiskBytes,
                        shizukuDiagnostics = formatShizukuDiagnostics(diagnostics),
                        items = items.sortedByDescending { item -> item.onDiskSizeBytes },
                        loadedKey = scanKey,
                        errorMessage = accessWarning
                    )
                }
            }.onFailure { throwable ->
                UiTrace.error("scanShizuku failed telegramOnly=$telegramOnly", throwable)
                updateIfCurrent(generation) {
                    it.copy(
                        isScanning = false,
                        errorMessage = throwable.message ?: "Shizuku scan failed"
                    )
                }
            }
        }
    }

    fun deleteItem(context: Context, item: StorageItem) {
        UiTrace.vm("deleteItem start path=${item.absolutePath} uri=${item.uri}")
        // Snapshot before dispatch: a scan finishing mid-delete must not send
        // the follow-up rescan to a different source than the delete used.
        val sourceAtDelete = _uiState.value.scanSource
        val telegramOnlyAtDelete = _uiState.value.shizukuTelegramOnly
        viewModelScope.launch(Dispatchers.IO) {
            val itemPath = item.absolutePath
            val ok = if (AndroidPrivateAccounting.isPrivatePath(itemPath)) {
                if (itemPath != null && shizukuBridge.canUseWithoutRequest()) {
                    runCatching {
                        shizukuBridge.deleteFile(context.applicationContext, itemPath)
                    }.getOrDefault(false)
                } else {
                    false
                }
            } else if (sourceAtDelete == ScanSource.ALL_FILES && itemPath != null) {
                scanner.deleteFile(itemPath)
            } else {
                scanner.delete(context.applicationContext, item.uri)
            }
            if (ok) {
                UiTrace.vm("deleteItem success path=${item.absolutePath}")
                if (sourceAtDelete == ScanSource.SHIZUKU_ANDROID) {
                    scanShizuku(context, telegramOnlyAtDelete)
                } else {
                    scan(context)
                }
            } else {
                UiTrace.vm("deleteItem failed path=${item.absolutePath}")
                _uiState.update { it.copy(errorMessage = "Failed to delete ${item.name}") }
            }
        }
    }

    fun scanAppStats(context: Context) {
        UiTrace.vm("scanAppStats start")
        _uiState.update {
            it.copy(
                scanSource = ScanSource.APP_STATS,
                selectedFolderUri = null,
                selectedRootPath = "/storage-map"
            ).startingScan()
        }
        val scanKey = _uiState.value.scanKey()
        val generation = beginScan()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val full = AppStorageStats.queryFull(context.applicationContext)
                    val items = AppStorageStats.toStorageItems(full)
                    val totalUsed = full.categories.totalUsed
                    val visibleAppsTotal = full.apps.sumOf { it.totalBytes }
                    val rootBytes = if (totalUsed > 0L) totalUsed else visibleAppsTotal
                    val expectedAppsTotal = full.categories.appSize + full.categories.appDataSize + full.categories.appCacheSize
                    val visibilityNote = if (expectedAppsTotal > 0L) {
                        val pct = (visibleAppsTotal * 100.0 / expectedAppsTotal.toDouble())
                        val pctText = String.format(java.util.Locale.US, "%.1f", pct)
                        if (pct < 5.0) {
                            "Apps visible: ${formatBytes(visibleAppsTotal)} / ${formatBytes(expectedAppsTotal)} ($pctText%). Check Usage Access permission for fuller per-app list."
                        } else {
                            "Apps visible: ${formatBytes(visibleAppsTotal)} / ${formatBytes(expectedAppsTotal)} ($pctText%)"
                        }
                    } else {
                        null
                    }
                    com.kvita.diskmapper.data.ScanResult(
                        items = items,
                        visitedNodes = items.size.toLong(),
                        rootLogicalSizeBytes = rootBytes,
                        rootOnDiskSizeBytes = rootBytes
                    ) to visibilityNote
                }
            }

            result.onSuccess { (scanResult, visibilityNote) ->
                UiTrace.vm("scanAppStats success items=${scanResult.items.size} total=${scanResult.rootOnDiskSizeBytes} note=$visibilityNote")
                updateIfCurrent(generation) {
                    it.copy(
                        isScanning = false,
                        visitedNodes = scanResult.visitedNodes,
                        rootLogicalSizeBytes = scanResult.rootLogicalSizeBytes,
                        rootOnDiskSizeBytes = scanResult.rootOnDiskSizeBytes,
                        items = scanResult.items,
                        loadedKey = scanKey,
                        shizukuDiagnostics = visibilityNote
                    )
                }
            }.onFailure { throwable ->
                UiTrace.error("scanAppStats failed", throwable)
                updateIfCurrent(generation) {
                    it.copy(
                        isScanning = false,
                        errorMessage = if (throwable is SecurityException)
                            "Usage access required. Enable it in Settings → Apps → Special access → Usage access → Disk Mapper"
                        else
                            throwable.message ?: "App stats scan failed"
                    )
                }
            }
        }
    }

    /**
     * Clears caches of ALL apps via Shizuku (`pm trim-caches`). Safe — apps
     * rebuild caches on demand. Reports freed bytes in a snackbar and rescans
     * app stats so the tree reflects the new state.
     */
    fun trimAllCaches(context: Context) {
        UiTrace.vm("trimAllCaches start")
        when (shizukuBridge.ensurePermission()) {
            ShizukuBridge.PermissionState.READY -> Unit
            ShizukuBridge.PermissionState.PERMISSION_REQUESTED -> {
                _uiState.update { it.copy(errorMessage = "Shizuku permission requested. Confirm and tap again.") }
                return
            }
            ShizukuBridge.PermissionState.PERMISSION_DENIED -> {
                _uiState.update { it.copy(errorMessage = "Shizuku permission denied.") }
                return
            }
            ShizukuBridge.PermissionState.SHIZUKU_NOT_RUNNING -> {
                _uiState.update { it.copy(errorMessage = "Shizuku is not running. Start Shizuku first.") }
                return
            }
        }

        // Shares the scan generation so trimming and a scan cannot clear each
        // other's spinner.
        val generation = beginScan()
        _uiState.update { it.copy(isScanning = true, errorMessage = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { shizukuBridge.trimCaches(context.applicationContext) }
            }
            result.onSuccess { raw ->
                UiTrace.vm("trimAllCaches result=$raw")
                if (raw.startsWith("ok;")) {
                    val freed = Regex("freedBytes=(\\d+)").find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    updateIfCurrent(generation) {
                        it.copy(isScanning = false, errorMessage = "Caches trimmed, freed ${formatBytes(freed)}")
                    }
                    if (_uiState.value.scanSource == ScanSource.APP_STATS) {
                        scanAppStats(context)
                    }
                } else {
                    updateIfCurrent(generation) {
                        it.copy(isScanning = false, errorMessage = "Trim caches failed: ${raw.removePrefix("err;")}")
                    }
                }
            }.onFailure { throwable ->
                UiTrace.error("trimAllCaches failed", throwable)
                updateIfCurrent(generation) {
                    it.copy(isScanning = false, errorMessage = throwable.message ?: "Trim caches failed")
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun parseShizukuPayload(payload: String): List<StorageItem> {
        if (payload.isBlank()) return emptyList()
        val separator = '\u001F'
        val byNormalizedPath = linkedMapOf<String, StorageItem>()

        payload
            .lineSequence()
            .forEach { line ->
                val parts = line.split(separator)
                if (parts.size < 5) return@forEach
                val rawPath = parts[0]
                val path = normalizeAndroidPath(rawPath)
                val name = parts[1]
                val logical = parts[2].toLongOrNull() ?: 0L
                val onDisk = parts[3].toLongOrNull() ?: logical
                val isDirectory = parts[4] == "1"
                val item = StorageItem(
                    uri = Uri.fromFile(File(path)),
                    absolutePath = path,
                    name = name,
                    logicalSizeBytes = logical,
                    onDiskSizeBytes = onDisk,
                    isDirectory = isDirectory,
                    mimeType = null
                )
                byNormalizedPath[path] = item
            }

        return byNormalizedPath.values.toList()
    }

    private fun normalizeAndroidPath(path: String): String {
        val storageRoot = sharedStorageRoot()
        return path
            .replace("/sdcard/", "$storageRoot/")
            .replace("/storage/self/primary/", "$storageRoot/")
    }

    private fun buildShizukuAccessWarning(diagnostics: String, items: List<StorageItem>): String? {
        val map = diagnostics
            .split(";")
            .mapNotNull {
                val idx = it.indexOf("=")
                if (idx <= 0) null else it.substring(0, idx) to it.substring(idx + 1)
            }
            .toMap()

        val uid = map["uid"]?.toIntOrNull()
        val dataEntries = map["dataEntries"]?.toIntOrNull() ?: -1
        val obbEntries = map["obbEntries"]?.toIntOrNull() ?: -1

        if (uid == 2000 && dataEntries <= 0 && obbEntries <= 0 && items.isNotEmpty()) {
            return "Shizuku runs as shell (uid 2000). Android/data access may be limited on this ROM; use root/Sui for full access."
        }
        if (uid == 2000 && items.isEmpty()) {
            return "No readable files in Android/data or Android/obb via shell Shizuku. Root/Sui backend is recommended."
        }
        if (items.isNotEmpty() && items.none { AndroidPrivateAccounting.isPrivateRoot(it.absolutePath) }) {
            return "Private scan returned no root totals. Results are incomplete; rescan after updating the app."
        }
        return null
    }

    private fun formatShizukuDiagnostics(diagnostics: String): String {
        val values = diagnostics
            .split(';')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) null else part.substring(0, separator) to part.substring(separator + 1)
            }
            .toMap()
        val uid = values["uid"]?.toIntOrNull()
        val dataEntries = values["dataEntries"]?.toIntOrNull() ?: -1
        val obbEntries = values["obbEntries"]?.toIntOrNull() ?: -1
        return when {
            uid == 0 -> "Private files: full root access"
            dataEntries >= 0 || obbEntries >= 0 ->
                "Private files: available (${maxOf(dataEntries, 0)} data, ${maxOf(obbEntries, 0)} obb entries)"
            else -> "Private files: limited by Android"
        }
    }

    private fun mergeRootAndShizuku(
        base: com.kvita.diskmapper.data.ScanResult,
        shizukuItems: List<StorageItem>
    ): com.kvita.diskmapper.data.ScanResult {
        if (shizukuItems.isEmpty()) return base

        val shizukuRoots = shizukuItems.filter { AndroidPrivateAccounting.isPrivateRoot(it.absolutePath) }
        if (shizukuRoots.isEmpty()) return base

        val mergedMap = linkedMapOf<String, StorageItem>()
        for (item in base.items.filterNot { AndroidPrivateAccounting.isPrivatePath(it.absolutePath) }) {
            val key = item.absolutePath ?: item.uri.toString()
            mergedMap[key] = item
        }
        for (item in shizukuItems) {
            val key = item.absolutePath ?: item.uri.toString()
            mergedMap[key] = item
        }

        val baseAndroidPrivate = base.items
            .filter { it.isDirectory && AndroidPrivateAccounting.isPrivateRoot(it.absolutePath) }
        val newTotals = AndroidPrivateAccounting.replaceRootTotals(
            base = ByteTotals(base.rootLogicalSizeBytes, base.rootOnDiskSizeBytes),
            basePrivate = AndroidPrivateAccounting.rootTotals(baseAndroidPrivate.map { it.toPathBytes() }),
            replacementPrivate = AndroidPrivateAccounting.rootTotals(shizukuRoots.map { it.toPathBytes() })
        )

        return com.kvita.diskmapper.data.ScanResult(
            items = mergedMap.values.sortedByDescending { it.onDiskSizeBytes },
            visitedNodes = base.visitedNodes + shizukuItems.size,
            rootLogicalSizeBytes = newTotals.logicalBytes,
            rootOnDiskSizeBytes = newTotals.onDiskBytes
        )
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var i = 0
        while (value >= 1024.0 && i < units.lastIndex) {
            value /= 1024.0
            i++
        }
        return String.format(Locale.US, "%.1f %s", value, units[i])
    }

    private fun StorageItem.toPathBytes(): PathBytes = PathBytes(
        path = absolutePath,
        logicalBytes = logicalSizeBytes,
        onDiskBytes = onDiskSizeBytes
    )

    private fun sharedStorageRoot(): String =
        Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
}
