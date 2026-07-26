package com.kvita.diskmapper.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.os.storage.StorageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kvita.diskmapper.data.StorageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/* ── constants ───────────────────────────────────────────────── */

/** Height of every tree row — Canvas lines use the same value so connectors
 *  touch perfectly across rows with zero gap.  */
private val ROW_HEIGHT = 32.dp

/** Horizontal step per tree depth level. */
private val INDENT_STEP = 14.dp

/** Color for tree branch guide lines. */
private val GUIDE_COLOR = Color(0xFF555E6B)

/** Background fill for the per-row size proportion bar. */
private val BAR_COLOR = Color(0x26FFC107)

private const val MAX_VISIBLE_ROWS = 2000

/* ── filters ─────────────────────────────────────────────────── */

enum class FileFilter { ALL, TELEGRAM, VIDEOS, ARCHIVES, INSTALLERS }

/* ── root screen ─────────────────────────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiskMapperScreen(vm: DiskMapperViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var filter by remember { mutableStateOf(FileFilter.ALL) }
    var pendingDelete by remember { mutableStateOf<StorageItem?>(null) }
    var pendingShizukuRetry by remember { mutableStateOf(false) }
    var pendingUsageRetry by remember { mutableStateOf(false) }
    var pendingAllFilesRetry by remember { mutableStateOf(false) }
    var pendingTrimCaches by remember { mutableStateOf(false) }
    var resumeTick by remember { mutableStateOf(0) }
    val expandedMap = remember { mutableStateMapOf<String, Boolean>() }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        UiTrace.ui("screen opened")
        vm.restorePersistedFolder(context)
    }

    DisposableEffect(
        lifecycleOwner,
        pendingShizukuRetry,
        pendingUsageRetry,
        pendingAllFilesRetry,
        filter
    ) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeTick++
                if (pendingShizukuRetry) {
                    UiTrace.ui("auto-retry shizuku scan on resume")
                    pendingShizukuRetry = false
                    vm.scanAndroidPrivateWithShizuku(context, filter == FileFilter.TELEGRAM)
                }
                if (pendingUsageRetry) {
                    UiTrace.ui("auto-retry app stats on resume")
                    pendingUsageRetry = false
                    if (vm.hasUsageAccess(context)) vm.scanAppStats(context)
                }
                if (pendingAllFilesRetry) {
                    UiTrace.ui("auto-retry shared files scan on resume")
                    pendingAllFilesRetry = false
                    if (hasAllFilesAccess()) {
                        vm.selectAllFilesRoot(sharedStorageRoot(), context)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val filteredItems = remember(state.items, filter) {
        state.items.filter { item ->
            when (filter) {
                FileFilter.ALL -> true
                FileFilter.TELEGRAM -> item.isTelegramRelated()
                FileFilter.VIDEOS -> item.mimeType?.startsWith("video/") == true ||
                    item.name.endsWith(".mp4", true) || item.name.endsWith(".mkv", true)
                FileFilter.ARCHIVES -> item.name.endsWith(".zip", true) ||
                    item.name.endsWith(".rar", true) || item.name.endsWith(".7z", true)
                FileFilter.INSTALLERS -> item.name.endsWith(".apk", true) ||
                    item.name.endsWith(".xapk", true)
            }
        }
    }

    val treeBasePath = remember(state.scanSource, state.selectedRootPath) {
        when (state.scanSource) {
            ScanSource.ALL_FILES -> state.selectedRootPath
            ScanSource.SHIZUKU_ANDROID -> state.selectedRootPath
            ScanSource.APP_STATS -> "/storage-map"
            ScanSource.SAF -> null
        }
    }
    val treeRoots by produceState<List<TreeNode>>(
        initialValue = emptyList(),
        filteredItems,
        treeBasePath
    ) {
        value = withContext(Dispatchers.Default) {
            buildTree(filteredItems, treeBasePath)
        }
    }
    val expandedSnapshot = expandedMap.toMap()
    val treeRows = remember(treeRoots, expandedSnapshot) {
        flattenTree(treeRoots, expandedSnapshot)
    }

    // Node paths are stable across filters and rescans, so only a different
    // scan target invalidates what the user has expanded. SAF has no base path,
    // so the picked folder identifies the target there.
    LaunchedEffect(state.scanSource, treeBasePath, state.selectedFolderUri) {
        expandedMap.clear()
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearError()
        }
    }

    val rootBytesForBars = maxOf(state.rootOnDiskSizeBytes, 1L)
    val accessStatus = remember(resumeTick) {
        buildString {
            append("Access  Files: ")
            append(if (hasAllFilesAccess()) "full" else "setup")
            append("  •  Apps: ")
            append(if (vm.hasUsageAccess(context)) "full" else "setup")
            append("  •  Private: ")
            append(if (vm.hasShizukuAccess()) "enabled" else "optional")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Disk Mapper", fontSize = 17.sp) },
                actions = {
                    IconButton(
                        onClick = {
                            UiTrace.ui("rescan click source=${state.scanSource}")
                            when (state.scanSource) {
                                ScanSource.SHIZUKU_ANDROID ->
                                    vm.scanAndroidPrivateWithShizuku(context, state.shizukuTelegramOnly)
                                ScanSource.APP_STATS ->
                                    vm.scanAppStats(context)
                                else ->
                                    vm.scan(context)
                            }
                        },
                        enabled = !state.isScanning &&
                            (state.selectedFolderUri != null ||
                                state.selectedRootPath != null ||
                                state.scanSource == ScanSource.SHIZUKU_ANDROID ||
                                state.scanSource == ScanSource.APP_STATS)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            /* ── top controls: action chips ── */
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ActionChip("Shared files", enabled = !state.isScanning) {
                    UiTrace.ui("action root-scan click")
                    if (hasAllFilesAccess()) {
                        vm.selectAllFilesRoot(sharedStorageRoot(), context)
                    } else {
                        UiTrace.ui("request MANAGE_EXTERNAL_STORAGE")
                        pendingAllFilesRetry = requestAllFilesAccess(context)
                    }
                }
                ActionChip("Private files", enabled = !state.isScanning) {
                    val telegramOnly = filter == FileFilter.TELEGRAM
                    UiTrace.ui("action shizuku-scan telegramOnly=$telegramOnly")
                    when (vm.scanAndroidPrivateWithShizuku(context, telegramOnly)) {
                        ShizukuBridge.PermissionState.READY,
                        ShizukuBridge.PermissionState.PERMISSION_DENIED -> Unit
                        ShizukuBridge.PermissionState.PERMISSION_REQUESTED,
                        ShizukuBridge.PermissionState.SHIZUKU_NOT_RUNNING -> {
                            pendingShizukuRetry = openShizukuApp(context)
                            UiTrace.ui("open shizuku app pendingRetry=$pendingShizukuRetry")
                        }
                    }
                }
                ActionChip("Apps", enabled = !state.isScanning) {
                    UiTrace.ui("action app-stats")
                    if (vm.hasUsageAccess(context)) {
                        vm.scanAppStats(context)
                    } else {
                        pendingUsageRetry = requestUsageAccess(context)
                    }
                }
                ActionChip("Clear caches", enabled = !state.isScanning) {
                    UiTrace.ui("action trim-caches click")
                    pendingTrimCaches = true
                }
            }

            /* ── filter chips row ── */
            // Apps mode hides the filters, so drop the selection instead of
            // silently reapplying it when the user returns to a file view.
            LaunchedEffect(state.scanSource) {
                if (state.scanSource == ScanSource.APP_STATS) filter = FileFilter.ALL
            }
            if (state.scanSource != ScanSource.APP_STATS) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (f in FileFilter.entries) {
                        FilterChip(
                            selected = filter == f,
                            onClick = {
                                UiTrace.ui("filter ${f.name}")
                                filter = f
                            },
                            label = { Text(f.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 12.sp) }
                        )
                    }
                }
            }

            Text(
                accessStatus,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
            )

            /* ── summary line ── */
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "D≈${fmtBytes(state.rootOnDiskSizeBytes)}  L:${fmtBytes(state.rootLogicalSizeBytes)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!state.shizukuDiagnostics.isNullOrBlank()) {
                    Text(
                        state.shizukuDiagnostics.orEmpty(),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            if (state.selectedRootPath != null) {
                Text(
                    "Root: ${state.selectedRootPath}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
            }

            /* ── scanning indicator ── */
            if (state.isScanning) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Scanning... ${state.visitedNodes}", fontSize = 12.sp)
                }
            }

            /* ── tree list ── */
            if (treeRows.isNotEmpty()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(treeRows.take(MAX_VISIBLE_ROWS), key = { it.node.path }) { row ->
                        TreeRowItem(
                            label = prettySegment(row.node.name.ifBlank { row.node.item?.name ?: "(folder)" }),
                            depth = row.depth,
                            guides = row.ancestorHasNext,
                            isLast = row.isLast,
                            item = row.node.item,
                            canExpand = row.node.children.isNotEmpty(),
                            expanded = expandedMap[row.node.path] ?: false,
                            onDiskBytes = row.node.onDiskSizeBytes,
                            logicalBytes = row.node.logicalSizeBytes,
                            sizeFraction = row.node.onDiskSizeBytes.toFloat() / rootBytesForBars,
                            allowDelete = state.scanSource != ScanSource.APP_STATS,
                            onToggle = {
                                val cur = expandedMap[row.node.path] ?: false
                                expandedMap[row.node.path] = !cur
                                UiTrace.ui("toggle path=${row.node.path} expanded=${!cur}")
                            },
                            onDelete = { row.node.item?.let { pendingDelete = it } }
                        )
                    }
                    if (treeRows.size > MAX_VISIBLE_ROWS) {
                        item {
                            Text(
                                "+ ${treeRows.size - MAX_VISIBLE_ROWS} more rows — collapse a branch or use a filter",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            } else if (!state.isScanning && filteredItems.isNotEmpty()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredItems.take(500), key = { it.uri.toString() }) { item ->
                        TreeRowItem(
                            label = item.name,
                            depth = 0,
                            guides = emptyList(),
                            isLast = true,
                            item = item,
                            canExpand = false,
                            expanded = false,
                            onDiskBytes = item.onDiskSizeBytes,
                            logicalBytes = item.logicalSizeBytes,
                            sizeFraction = item.onDiskSizeBytes.toFloat() / rootBytesForBars,
                            allowDelete = state.scanSource != ScanSource.APP_STATS,
                            onToggle = {},
                            onDelete = { pendingDelete = item }
                        )
                    }
                }
            }
        }
    }

    /* ── trim caches dialog ── */
    if (pendingTrimCaches) {
        val privilegedCleanup = vm.hasShizukuAccess()
        AlertDialog(
            onDismissRequest = { pendingTrimCaches = false },
            title = { Text("Clear app caches") },
            text = {
                Text(
                    if (privilegedCleanup) {
                        "Clear caches of all apps using private access? Apps rebuild caches on demand. This can take up to a minute."
                    } else {
                        "Open Android's cache cleanup dialog? Android will ask for confirmation. Private access is not required."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    UiTrace.ui("trim-caches confirm")
                    pendingTrimCaches = false
                    if (privilegedCleanup) {
                        vm.trimAllCaches(context)
                    } else {
                        openSystemCacheCleanup(context)
                    }
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = {
                    UiTrace.ui("trim-caches canceled")
                    pendingTrimCaches = false
                }) { Text("Cancel") }
            }
        )
    }

    /* ── delete dialog ── */
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete file") },
            text = { Text("Delete ${pendingDelete?.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    UiTrace.ui("delete confirm item=${pendingDelete?.absolutePath ?: pendingDelete?.name}")
                    pendingDelete?.let { vm.deleteItem(context, it) }
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = {
                    UiTrace.ui("delete canceled")
                    pendingDelete = null
                }) { Text("Cancel") }
            }
        )
    }
}

/* ── chips ────────────────────────────────────────────────────── */

@Composable
private fun ActionChip(title: String, enabled: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(title, fontSize = 12.sp) }
    )
}

/* ── single tree row ─────────────────────────────────────────── */

@Composable
private fun TreeRowItem(
    label: String,
    depth: Int,
    guides: List<Boolean>,
    isLast: Boolean,
    item: StorageItem?,
    canExpand: Boolean,
    expanded: Boolean,
    onDiskBytes: Long,
    logicalBytes: Long,
    sizeFraction: Float,
    allowDelete: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val isDir = item?.isDirectory == true || canExpand

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clickable(enabled = canExpand) { onToggle() }
    ) {
        // TreeSize-style proportion bar: row background filled by share of root
        if (sizeFraction > 0.005f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(sizeFraction.coerceIn(0f, 1f))
                    .background(BAR_COLOR)
            )
        }
        TreeRowContent(
            label = label,
            depth = depth,
            guides = guides,
            isLast = isLast,
            item = item,
            canExpand = canExpand,
            expanded = expanded,
            onDiskBytes = onDiskBytes,
            logicalBytes = logicalBytes,
            isDir = isDir,
            allowDelete = allowDelete,
            onDelete = onDelete
        )
    }
}

@Composable
private fun TreeRowContent(
    label: String,
    depth: Int,
    guides: List<Boolean>,
    isLast: Boolean,
    item: StorageItem?,
    canExpand: Boolean,
    expanded: Boolean,
    onDiskBytes: Long,
    logicalBytes: Long,
    isDir: Boolean,
    allowDelete: Boolean,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        /* ── left: tree guides + icon + name ── */
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // tree branch lines — one column per ancestor + one for current node
            if (depth > 0) {
                TreeGuides(guides = guides, isLast = isLast)
            }

            // expand arrow or spacer
            if (canExpand) {
                Text(
                    if (expanded) "\u25BE" else "\u25B8",
                    fontSize = 11.sp,
                    modifier = Modifier.width(12.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                Spacer(Modifier.width(12.dp))
            }

            // folder / file icon
            Icon(
                imageVector = if (isDir) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (isDir) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(3.dp))

            // name
            Text(
                label,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        /* ── right: sizes + delete ── */
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("D≈${fmtBytes(onDiskBytes)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
            Text(
                "L:${fmtBytes(logicalBytes)}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (allowDelete && item != null && !item.isDirectory) {
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

/* ── tree guide lines (X-plore style) ────────────────────────── */

/**
 * Draws the tree connector lines for a single row.
 *
 * [guides] — one boolean per ancestor depth level. `true` = that ancestor still
 * has siblings below it → draw a full-height vertical line. `false` = gap.
 *
 * [isLast] — whether the current node is the last child at its level.
 *  • last child   → L-corner (half vertical + horizontal)
 *  • other child  → T-branch (full vertical + horizontal)
 *
 * Each column is [INDENT_STEP] wide × [ROW_HEIGHT] tall — matching the row
 * height exactly so vertical lines connect across consecutive rows with no gap.
 */
@Composable
private fun TreeGuides(guides: List<Boolean>, isLast: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // ancestor continuation columns
        for (hasNext in guides) {
            Box(modifier = Modifier.size(INDENT_STEP, ROW_HEIGHT)) {
                if (hasNext) {
                    Canvas(Modifier.fillMaxSize()) {
                        val x = size.width / 2f
                        drawLine(GUIDE_COLOR, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    }
                }
            }
        }
        // current node column: T-branch or L-corner
        Box(modifier = Modifier.size(INDENT_STEP, ROW_HEIGHT)) {
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width / 2f
                val yMid = size.height / 2f
                // vertical part
                if (isLast) {
                    // L-corner: top → mid
                    drawLine(GUIDE_COLOR, Offset(x, 0f), Offset(x, yMid), strokeWidth = 1f)
                } else {
                    // T-branch: top → bottom
                    drawLine(GUIDE_COLOR, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                }
                // horizontal stub: mid → right
                drawLine(GUIDE_COLOR, Offset(x, yMid), Offset(size.width, yMid), strokeWidth = 1f)
            }
        }
    }
}

/** Human-readable names for synthetic /storage-map tree segments. */
private fun prettySegment(name: String): String = when (name) {
    "per-app" -> "By app (APK+data+cache)"
    "apps-apk-by-app" -> "APK by app"
    "apps-data-by-app" -> "Data by app"
    "apps-cache-by-app" -> "Cache by app"
    "categories" -> "Categories"
    "storage-map" -> "Storage map"
    else -> name
}

/* ── formatting ──────────────────────────────────────────────── */

private fun fmtBytes(bytes: Long): String {
    if (bytes <= 0) return "0"
    val units = arrayOf("B", "K", "M", "G", "T")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024.0; i++ }
    return if (i == 0) "${bytes}B"
    else String.format(Locale.US, "%.1f%s", v, units[i])
}

@Suppress("unused")
private fun formatBytes(bytes: Long): String = fmtBytes(bytes)

private fun StorageItem.isTelegramRelated(): Boolean {
    val lowerName = name.lowercase(Locale.ROOT)
    val lowerUri = uri.toString().lowercase(Locale.ROOT)
    val lowerPath = (absolutePath ?: "").lowercase(Locale.ROOT)

    val pathMatch = lowerUri.contains("telegram") ||
        lowerPath.contains("telegram") ||
        lowerUri.contains("org.telegram.messenger") ||
        lowerUri.contains("org.telegram.plus") ||
        lowerPath.contains("/android/data/org.telegram.messenger") ||
        lowerPath.contains("/android/media/org.telegram.messenger")

    val tgFileTypeMatch = lowerName.endsWith(".tgs") ||
        lowerName.endsWith(".webm") ||
        lowerName.endsWith(".oga") ||
        lowerName.endsWith(".opus")

    return pathMatch || tgFileTypeMatch
}

private fun hasAllFilesAccess(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

private fun sharedStorageRoot(): String =
    Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')

private fun requestAllFilesAccess(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    val intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )
    val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrElse {
        runCatching {
            context.startActivity(fallback)
            true
        }.getOrDefault(false)
    }
}

private fun requestUsageAccess(context: android.content.Context): Boolean {
    val intent = Intent(
        Settings.ACTION_USAGE_ACCESS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    )
    val fallback = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrElse {
        runCatching {
            context.startActivity(fallback)
            true
        }.getOrDefault(false)
    }
}

private fun openSystemCacheCleanup(context: android.content.Context): Boolean {
    val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        StorageManager.ACTION_CLEAR_APP_CACHE
    } else {
        StorageManager.ACTION_MANAGE_STORAGE
    }
    return runCatching {
        context.startActivity(Intent(action))
        true
    }.getOrElse {
        runCatching {
            context.startActivity(Intent(StorageManager.ACTION_MANAGE_STORAGE))
            true
        }.getOrDefault(false)
    }
}

private data class TreeNode(
    val path: String,
    val name: String,
    var item: StorageItem? = null,
    var logicalSizeBytes: Long = 0L,
    var onDiskSizeBytes: Long = 0L,
    val children: MutableList<TreeNode> = mutableListOf()
)

private data class TreeRow(
    val node: TreeNode,
    val depth: Int,
    val ancestorHasNext: List<Boolean>,
    val isLast: Boolean
)

private fun buildTree(items: List<StorageItem>, basePath: String?): List<TreeNode> {
    val pathItems = items.filter { !it.absolutePath.isNullOrBlank() }
    if (pathItems.isEmpty()) return emptyList()

    val root = TreeNode(path = "", name = "")
    val nodeMap = hashMapOf("" to root)

    for (item in pathItems) {
        val abs = item.absolutePath ?: continue
        val relative = toRelativePath(abs, basePath)
        if (relative.isBlank()) continue
        val parts = relative.split('/').filter { it.isNotBlank() }

        var currentPath = ""
        var parent = root
        for (part in parts) {
            currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
            val existing = nodeMap[currentPath]
            val node = if (existing != null) existing else {
                val created = TreeNode(path = currentPath, name = part)
                nodeMap[currentPath] = created
                parent.children += created
                created
            }
            parent = node
        }

        parent.item = item
        parent.logicalSizeBytes = item.logicalSizeBytes
        parent.onDiskSizeBytes = item.onDiskSizeBytes
    }

    aggregateTree(root)
    root.children.forEach { sortNode(it) }
    return root.children.sortedByDescending { it.onDiskSizeBytes }
}

private fun flattenTree(roots: List<TreeNode>, expanded: Map<String, Boolean>): List<TreeRow> {
    // Order comes from buildTree/sortNode; re-sorting here would repeat it on
    // every expand/collapse.
    val out = mutableListOf<TreeRow>()
    for ((index, root) in roots.withIndex()) {
        appendNode(
            node = root,
            depth = 0,
            expanded = expanded,
            out = out,
            ancestorHasNext = emptyList(),
            isLast = index == roots.lastIndex
        )
    }
    return out
}

private fun appendNode(
    node: TreeNode,
    depth: Int,
    expanded: Map<String, Boolean>,
    out: MutableList<TreeRow>,
    ancestorHasNext: List<Boolean>,
    isLast: Boolean
) {
    out += TreeRow(
        node = node,
        depth = depth,
        ancestorHasNext = ancestorHasNext,
        isLast = isLast
    )
    val isExpanded = expanded[node.path] ?: false
    if (isExpanded) {
        val children = node.children
        for ((index, child) in children.withIndex()) {
            appendNode(
                node = child,
                depth = depth + 1,
                expanded = expanded,
                out = out,
                ancestorHasNext = ancestorHasNext + (!isLast),
                isLast = index == children.lastIndex
            )
        }
    }
}

private fun sortNode(node: TreeNode) {
    node.children.sortByDescending { it.onDiskSizeBytes }
    node.children.forEach { sortNode(it) }
}

private fun aggregateTree(node: TreeNode): Long {
    val itemLogical = node.item?.logicalSizeBytes ?: 0L
    val itemOnDisk = node.item?.onDiskSizeBytes ?: 0L
    var logicalSum = if (node.children.isEmpty()) itemLogical else 0L
    var onDiskSum = if (node.children.isEmpty()) itemOnDisk else 0L
    for (child in node.children) {
        aggregateTree(child)
        logicalSum += child.logicalSizeBytes
        onDiskSum += child.onDiskSizeBytes
    }
    node.logicalSizeBytes = maxOf(node.logicalSizeBytes, logicalSum, itemLogical)
    node.onDiskSizeBytes = maxOf(node.onDiskSizeBytes, onDiskSum, itemOnDisk)
    return node.onDiskSizeBytes
}

private fun openShizukuApp(context: android.content.Context): Boolean {
    val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
    if (launch != null) {
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(launch) }
        return true
    }
    return false
}

private fun toRelativePath(absPath: String, basePath: String?): String {
    val normalizedAbs = absPath.replace('\\', '/').trim('/')
    val normalizedBase = basePath?.replace('\\', '/')?.trim('/') ?: ""
    if (normalizedBase.isNotBlank() && normalizedAbs.startsWith(normalizedBase)) {
        return normalizedAbs.removePrefix(normalizedBase).trim('/')
    }
    return normalizedAbs
}
