package com.kvita.diskmapper.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kvita.diskmapper.data.StorageItem
import java.util.Locale

/* ── constants ───────────────────────────────────────────────── */

/** Height of every tree row — Canvas lines use the same value so connectors
 *  touch perfectly across rows with zero gap.  */
private val ROW_HEIGHT = 22.dp

/** Horizontal step per tree depth level. */
private val INDENT_STEP = 14.dp

/** Color for tree branch guide lines. */
private val GUIDE_COLOR = Color(0xFF888888)

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
    val expandedMap = remember { mutableStateMapOf<String, Boolean>() }

    val folderPicker = rememberLauncherForActivityResult(OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) {
            UiTrace.ui("folderPicker canceled")
        } else {
            UiTrace.ui("folderPicker selected uri=$uri")
            vm.selectFolder(context, uri)
        }
    }

    LaunchedEffect(Unit) {
        UiTrace.ui("screen opened")
        vm.restorePersistedFolder(context)
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
            ScanSource.SHIZUKU_ANDROID -> "/storage/emulated/0/Android"
            ScanSource.APP_STATS -> "/storage-map"
            ScanSource.SAF -> null
        }
    }
    val treeRoots = remember(filteredItems, treeBasePath) {
        buildTree(filteredItems, treeBasePath)
    }
    val treeRows = flattenTree(treeRoots, expandedMap.toMap())

    LaunchedEffect(treeRoots) { expandedMap.clear() }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Disk Mapper") },
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
                ActionChip(
                    title = if (state.selectedFolderUri == null) "Folder" else "Change",
                    enabled = !state.isScanning
                ) {
                    UiTrace.ui("action select-folder")
                    folderPicker.launch(null)
                }
                ActionChip("Root scan", enabled = !state.isScanning) {
                    UiTrace.ui("action root-scan click")
                    if (hasAllFilesAccess()) {
                        vm.selectAllFilesRoot("/storage/emulated/0", context)
                    } else {
                        UiTrace.ui("request MANAGE_EXTERNAL_STORAGE")
                        requestAllFilesAccess(context)
                    }
                }
                ActionChip("Shizuku", enabled = !state.isScanning) {
                    val telegramOnly = filter == FileFilter.TELEGRAM
                    UiTrace.ui("action shizuku-scan telegramOnly=$telegramOnly")
                    vm.scanAndroidPrivateWithShizuku(context, telegramOnly)
                }
                ActionChip("Apps", enabled = !state.isScanning) {
                    UiTrace.ui("action app-stats")
                    vm.scanAppStats(context)
                }
            }

            /* ── filter chips row ── */
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

            /* ── summary line ── */
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "D:${fmtBytes(state.rootOnDiskSizeBytes)}  L:${fmtBytes(state.rootLogicalSizeBytes)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!state.shizukuDiagnostics.isNullOrBlank()) {
                    Text(
                        "Shizuku: ${state.shizukuDiagnostics}",
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
                    items(treeRows.take(2000), key = { it.node.path }) { row ->
                        TreeRowItem(
                            label = row.node.name.ifBlank { row.node.item?.name ?: "(folder)" },
                            depth = row.depth,
                            guides = row.ancestorHasNext,
                            isLast = row.isLast,
                            item = row.node.item,
                            canExpand = row.node.children.isNotEmpty(),
                            expanded = expandedMap[row.node.path] ?: false,
                            onDiskBytes = row.node.onDiskSizeBytes,
                            logicalBytes = row.node.logicalSizeBytes,
                            onToggle = {
                                val cur = expandedMap[row.node.path] ?: false
                                expandedMap[row.node.path] = !cur
                                UiTrace.ui("toggle path=${row.node.path} expanded=${!cur}")
                            },
                            onDelete = { row.node.item?.let { pendingDelete = it } }
                        )
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
                            onToggle = {},
                            onDelete = { pendingDelete = item }
                        )
                    }
                }
            }
        }
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
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val isDir = item?.isDirectory == true || canExpand

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clickable(enabled = canExpand) { onToggle() }
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
                imageVector = if (isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
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
            Text("D:${fmtBytes(onDiskBytes)}", fontSize = 10.sp)
            Text(
                "L:${fmtBytes(logicalBytes)}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (item != null && !item.isDirectory) {
                IconButton(onClick = onDelete, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(12.dp))
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

private fun requestAllFilesAccess(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )
    val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching { context.startActivity(fallback) }
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
    val out = mutableListOf<TreeRow>()
    val sortedRoots = roots.sortedByDescending { it.onDiskSizeBytes }
    for ((index, root) in sortedRoots.withIndex()) {
        appendNode(
            node = root,
            depth = 0,
            expanded = expanded,
            out = out,
            ancestorHasNext = emptyList(),
            isLast = index == sortedRoots.lastIndex
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
        val children = node.children.sortedByDescending { it.onDiskSizeBytes }
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

private fun toRelativePath(absPath: String, basePath: String?): String {
    val normalizedAbs = absPath.replace('\\', '/').trim('/')
    val normalizedBase = basePath?.replace('\\', '/')?.trim('/') ?: ""
    if (normalizedBase.isNotBlank() && normalizedAbs.startsWith(normalizedBase)) {
        return normalizedAbs.removePrefix(normalizedBase).trim('/')
    }
    return normalizedAbs
}

