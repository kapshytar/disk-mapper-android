package com.kvita.diskmapper.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

enum class ScanSource {
    SAF,
    ALL_FILES,
    SHIZUKU_ANDROID
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
    val errorMessage: String? = null
)

class DiskMapperViewModel : ViewModel() {
    private val scanner = StorageScanner()
    private val shizukuBridge = ShizukuBridge()

    private val _uiState = MutableStateFlow(DiskMapperUiState())
    val uiState: StateFlow<DiskMapperUiState> = _uiState.asStateFlow()

    fun restorePersistedFolder(context: Context) {
        if (_uiState.value.selectedFolderUri != null || _uiState.value.selectedRootPath != null) return
        val persisted = context.contentResolver.persistedUriPermissions.firstOrNull()?.uri ?: return
        _uiState.update { it.copy(selectedFolderUri = persisted, scanSource = ScanSource.SAF) }
        scan(context)
    }

    fun selectFolder(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
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
        val warning = if (path == "/storage/emulated/0") {
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

    fun scanAndroidPrivateWithShizuku(context: Context, telegramOnly: Boolean) {
        when (shizukuBridge.ensurePermission()) {
            ShizukuBridge.PermissionState.SHIZUKU_NOT_RUNNING -> {
                _uiState.update {
                    it.copy(errorMessage = "Shizuku is not running. Start Shizuku first.")
                }
            }
            ShizukuBridge.PermissionState.PERMISSION_REQUESTED -> {
                _uiState.update {
                    it.copy(errorMessage = "Shizuku permission requested. Confirm and tap again.")
                }
            }
            ShizukuBridge.PermissionState.PERMISSION_DENIED -> {
                _uiState.update {
                    it.copy(errorMessage = "Shizuku permission denied.")
                }
            }
            ShizukuBridge.PermissionState.READY -> {
                _uiState.update {
                    it.copy(
                        selectedFolderUri = null,
                        selectedRootPath = "/storage/emulated/0/Android",
                        scanSource = ScanSource.SHIZUKU_ANDROID,
                        shizukuTelegramOnly = telegramOnly,
                        shizukuDiagnostics = null,
                        errorMessage = null
                    )
                }
                scanShizuku(context, telegramOnly)
            }
        }
    }

    fun scan(context: Context) {
        val state = _uiState.value

        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, visitedNodes = 0, errorMessage = null) }

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (state.scanSource) {
                        ScanSource.SAF -> {
                            val rootUri = state.selectedFolderUri
                                ?: return@runCatching throw IllegalStateException("Folder is not selected")
                            scanner.scan(context.applicationContext, rootUri) { visited ->
                                _uiState.update { it.copy(visitedNodes = visited) }
                            }
                        }
                        ScanSource.ALL_FILES -> {
                            val rootPath = state.selectedRootPath
                                ?: return@runCatching throw IllegalStateException("Root path is not selected")
                            val baseScan = scanner.scanFileTree(File(rootPath)) { visited ->
                                _uiState.update { it.copy(visitedNodes = visited) }
                            }
                            if (rootPath == "/storage/emulated/0" && shizukuBridge.canUseWithoutRequest()) {
                                val payload = shizukuBridge.scanAndroidPrivate(context.applicationContext, false)
                                val shizukuItems = parseShizukuPayload(payload)
                                mergeRootAndShizuku(baseScan, shizukuItems)
                            } else {
                                baseScan
                            }
                        }
                        ScanSource.SHIZUKU_ANDROID -> {
                            return@runCatching throw IllegalStateException(
                                "Use Shizuku scan action for Android/data and Android/obb."
                            )
                        }
                    }
                }
            }

            result.onSuccess { scanResult ->
                _uiState.update {
                    it.copy(
                    isScanning = false,
                    visitedNodes = scanResult.visitedNodes,
                    rootLogicalSizeBytes = scanResult.rootLogicalSizeBytes,
                    rootOnDiskSizeBytes = scanResult.rootOnDiskSizeBytes,
                    items = scanResult.items
                )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                    isScanning = false,
                    errorMessage = throwable.message ?: "Scan failed"
                )
                }
            }
        }
    }

    private fun scanShizuku(context: Context, telegramOnly: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, visitedNodes = 0, errorMessage = null) }

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val diagnostics = shizukuBridge.diagnostics(context.applicationContext)
                    val payload = shizukuBridge.scanAndroidPrivate(context.applicationContext, telegramOnly)
                    Pair(diagnostics, parseShizukuPayload(payload))
                }
            }

            result.onSuccess { (diagnostics, items) ->
                val logical = items.sumOf { it.logicalSizeBytes }
                val onDisk = items.sumOf { it.onDiskSizeBytes }
                val accessWarning = buildShizukuAccessWarning(diagnostics, items)
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        visitedNodes = items.size.toLong(),
                        rootLogicalSizeBytes = logical,
                        rootOnDiskSizeBytes = onDisk,
                        shizukuDiagnostics = diagnostics,
                        items = items.sortedByDescending { item -> item.onDiskSizeBytes },
                        errorMessage = accessWarning
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        errorMessage = throwable.message ?: "Shizuku scan failed"
                    )
                }
            }
        }
    }

    fun deleteItem(context: Context, item: StorageItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = if (_uiState.value.scanSource == ScanSource.ALL_FILES && item.absolutePath != null) {
                scanner.deleteFile(item.absolutePath)
            } else if (_uiState.value.scanSource == ScanSource.SHIZUKU_ANDROID && item.absolutePath != null) {
                runCatching {
                    shizukuBridge.deleteFile(context.applicationContext, item.absolutePath)
                }.getOrDefault(false)
            } else {
                scanner.delete(context.applicationContext, item.uri)
            }
            if (ok) {
                if (_uiState.value.scanSource == ScanSource.SHIZUKU_ANDROID) {
                    scanShizuku(context, _uiState.value.shizukuTelegramOnly)
                } else {
                    scan(context)
                }
            } else {
                _uiState.update { it.copy(errorMessage = "Failed to delete ${item.name}") }
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
        return path
            .replace("/sdcard/", "/storage/emulated/0/")
            .replace("/storage/self/primary/", "/storage/emulated/0/")
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
        return null
    }

    private fun mergeRootAndShizuku(
        base: com.kvita.diskmapper.data.ScanResult,
        shizukuItems: List<StorageItem>
    ): com.kvita.diskmapper.data.ScanResult {
        if (shizukuItems.isEmpty()) return base

        val mergedMap = linkedMapOf<String, StorageItem>()
        for (item in base.items) {
            val key = item.absolutePath ?: item.uri.toString()
            mergedMap[key] = item
        }
        for (item in shizukuItems) {
            val key = item.absolutePath ?: item.uri.toString()
            mergedMap[key] = item
        }

        val baseAndroidPrivate = base.items
            .filter { it.isDirectory && (it.absolutePath == "/storage/emulated/0/Android/data" || it.absolutePath == "/storage/emulated/0/Android/obb") }
        val baseLogicalPrivate = baseAndroidPrivate.sumOf { it.logicalSizeBytes }
        val baseOnDiskPrivate = baseAndroidPrivate.sumOf { it.onDiskSizeBytes }
        val shizukuAndroidPrivate = shizukuItems
            .filter { it.isDirectory && (it.absolutePath == "/storage/emulated/0/Android/data" || it.absolutePath == "/storage/emulated/0/Android/obb") }
        val shizukuLogicalPrivate = shizukuAndroidPrivate.sumOf { it.logicalSizeBytes }
        val shizukuOnDiskPrivate = shizukuAndroidPrivate.sumOf { it.onDiskSizeBytes }

        val newLogical = base.rootLogicalSizeBytes - baseLogicalPrivate + shizukuLogicalPrivate
        val newOnDisk = base.rootOnDiskSizeBytes - baseOnDiskPrivate + shizukuOnDiskPrivate

        return com.kvita.diskmapper.data.ScanResult(
            items = mergedMap.values.sortedByDescending { it.onDiskSizeBytes },
            visitedNodes = base.visitedNodes + shizukuItems.size,
            rootLogicalSizeBytes = newLogical.coerceAtLeast(0L),
            rootOnDiskSizeBytes = newOnDisk.coerceAtLeast(0L)
        )
    }
}

