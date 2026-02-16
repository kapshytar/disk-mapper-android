package com.kvita.diskmapper.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kvita.diskmapper.data.StorageItem
import java.util.Locale

enum class FileFilter {
    ALL, TELEGRAM, VIDEOS, ARCHIVES, INSTALLERS
}

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
                FileFilter.VIDEOS -> item.mimeType?.startsWith("video/") == true || item.name.endsWith(".mp4", true) || item.name.endsWith(".mkv", true)
                FileFilter.ARCHIVES -> item.name.endsWith(".zip", true) || item.name.endsWith(".rar", true) || item.name.endsWith(".7z", true)
                FileFilter.INSTALLERS -> item.name.endsWith(".apk", true) || item.name.endsWith(".xapk", true)
            }
        }
    }

    val treeBasePath = remember(state.scanSource, state.selectedRootPath) {
        when (state.scanSource) {
            ScanSource.ALL_FILES -> state.selectedRootPath
            ScanSource.SHIZUKU_ANDROID -> "/storage/emulated/0/Android"
            ScanSource.SAF -> null
        }
    }
    val treeRoots = remember(filteredItems, treeBasePath) {
        buildTree(filteredItems, treeBasePath)
    }
    val treeRows = flattenTree(treeRoots, expandedMap.toMap())

    LaunchedEffect(treeRoots) {
        expandedMap.clear()
    }

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
                            if (state.scanSource == ScanSource.SHIZUKU_ANDROID) {
                                vm.scanAndroidPrivateWithShizuku(context, state.shizukuTelegramOnly)
                            } else {
                                vm.scan(context)
                            }
                        },
                        enabled = !state.isScanning &&
                            (state.selectedFolderUri != null ||
                                state.selectedRootPath != null ||
                                state.scanSource == ScanSource.SHIZUKU_ANDROID)
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    ActionChip(
                        title = if (state.selectedFolderUri == null) "Select folder" else "Change folder",
                        enabled = !state.isScanning
                    ) {
                        UiTrace.ui("action select-folder")
                        folderPicker.launch(null)
                    }
                }
                item {
                    ActionChip(
                        title = "Root scan",
                        enabled = !state.isScanning
                    ) {
                        UiTrace.ui("action root-scan click")
                        if (hasAllFilesAccess()) {
                            vm.selectAllFilesRoot("/storage/emulated/0", context)
                        } else {
                            UiTrace.ui("request MANAGE_EXTERNAL_STORAGE")
                            requestAllFilesAccess(context)
                        }
                    }
                }
                item {
                    ActionChip(
                        title = "Shizuku Android/",
                        enabled = !state.isScanning
                    ) {
                        val telegramOnly = filter == FileFilter.TELEGRAM
                        UiTrace.ui("action shizuku-scan telegramOnly=$telegramOnly")
                        vm.scanAndroidPrivateWithShizuku(context, telegramOnly)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "D: ${formatBytes(state.rootOnDiskSizeBytes)}  L: ${formatBytes(state.rootLogicalSizeBytes)}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (!state.shizukuDiagnostics.isNullOrBlank()) {
                    Text(
                        text = "Shizuku: ${state.shizukuDiagnostics}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (state.selectedRootPath != null) {
                Text(text = "Root: ${state.selectedRootPath}", style = MaterialTheme.typography.bodySmall)
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip("All", filter == FileFilter.ALL) {
                        UiTrace.ui("filter ALL")
                        filter = FileFilter.ALL
                    }
                }
                item {
                    FilterChip("Telegram", filter == FileFilter.TELEGRAM) {
                        UiTrace.ui("filter TELEGRAM")
                        filter = FileFilter.TELEGRAM
                    }
                }
                item {
                    FilterChip("Videos", filter == FileFilter.VIDEOS) {
                        UiTrace.ui("filter VIDEOS")
                        filter = FileFilter.VIDEOS
                    }
                }
                item {
                    FilterChip("Archives", filter == FileFilter.ARCHIVES) {
                        UiTrace.ui("filter ARCHIVES")
                        filter = FileFilter.ARCHIVES
                    }
                }
                item {
                    FilterChip("Installers", filter == FileFilter.INSTALLERS) {
                        UiTrace.ui("filter INSTALLERS")
                        filter = FileFilter.INSTALLERS
                    }
                }
            }

            if (state.isScanning) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text("Scanning... nodes: ${state.visitedNodes}")
                }
            }

            if (treeRows.isNotEmpty()) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    items(treeRows.take(500), key = { it.node.path }) { row ->
                        ItemCard(
                            label = row.node.name.ifBlank { row.node.item?.name ?: "(folder)" },
                            depth = row.depth,
                            ancestorHasNext = row.ancestorHasNext,
                            isLast = row.isLast,
                            item = row.node.item,
                            canExpand = row.node.children.isNotEmpty(),
                            expanded = expandedMap[row.node.path] ?: false,
                            displayOnDiskBytes = row.node.onDiskSizeBytes,
                            displayLogicalBytes = row.node.logicalSizeBytes,
                            onToggleExpand = {
                                val current = expandedMap[row.node.path] ?: false
                                expandedMap[row.node.path] = !current
                                UiTrace.ui("toggle path=${row.node.path} expanded=${!current}")
                            },
                            onDelete = { row.node.item?.let { pendingDelete = it } }
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    items(filteredItems.take(300), key = { it.uri.toString() }) { item ->
                        ItemCard(
                            label = item.name,
                            depth = 0,
                            ancestorHasNext = emptyList(),
                            isLast = true,
                            item = item,
                            canExpand = false,
                            expanded = false,
                            displayOnDiskBytes = item.onDiskSizeBytes,
                            displayLogicalBytes = item.logicalSizeBytes,
                            onToggleExpand = {},
                            onDelete = { pendingDelete = item }
                        )
                    }
                }
            }
        }
    }

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

@Composable
private fun FilterChip(title: String, selected: Boolean, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(title) }, leadingIcon = if (selected) ({ Text("*") }) else null)
}

@Composable
private fun ActionChip(title: String, enabled: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(title) }
    )
}

@Composable
private fun ItemCard(
    label: String,
    depth: Int,
    ancestorHasNext: List<Boolean>,
    isLast: Boolean,
    item: StorageItem?,
    canExpand: Boolean,
    expanded: Boolean,
    displayOnDiskBytes: Long,
    displayLogicalBytes: Long,
    onToggleExpand: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canExpand) { onToggleExpand() }
            .height(24.dp)
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (depth > 0) {
                TreeIndent(ancestorHasNext = ancestorHasNext, isLast = isLast)
            }
            if (canExpand) {
                Text(
                    if (expanded) "▾" else "▸",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Spacer(modifier = Modifier.width(8.dp))
            }
            val isDir = item?.isDirectory == true || canExpand
            Icon(
                imageVector = if (isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = if (isDir) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                label,
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall
            )
            if (isDir) {
                Spacer(modifier = Modifier.width(2.dp))
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "D:${formatBytes(displayOnDiskBytes)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "L:${formatBytes(displayLogicalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (item != null && !item.isDirectory) {
                IconButton(onClick = onDelete, modifier = Modifier.size(20.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TreeIndent(
    ancestorHasNext: List<Boolean>,
    isLast: Boolean,
    step: Dp = 10.dp
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (hasNext in ancestorHasNext) {
            Box(modifier = Modifier.size(step, 20.dp)) {
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    if (hasNext) {
                        val x = size.width / 2f
                        drawLine(
                            color = Color(0xFF7A7A7A),
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1f
                        )
                    }
                }
            }
        }
        Box(modifier = Modifier.size(step, 20.dp)) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val x = size.width / 2f
                val yMid = size.height / 2f
                val color = Color(0xFF7A7A7A)
                if (!isLast) {
                    drawLine(color = color, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 1f)
                } else {
                    drawLine(color = color, start = Offset(x, 0f), end = Offset(x, yMid), strokeWidth = 1f)
                }
                drawLine(color = color, start = Offset(x, yMid), end = Offset(size.width, yMid), strokeWidth = 1f)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024.0
        i++
    }
    return String.format(Locale.US, "%.2f %s", value, units[i])
}

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
    var logicalSum = node.item?.logicalSizeBytes ?: 0L
    var onDiskSum = node.item?.onDiskSizeBytes ?: 0L
    for (child in node.children) {
        aggregateTree(child)
        logicalSum += child.logicalSizeBytes
        onDiskSum += child.onDiskSizeBytes
    }
    node.logicalSizeBytes = maxOf(node.logicalSizeBytes, logicalSum)
    node.onDiskSizeBytes = maxOf(node.onDiskSizeBytes, onDiskSum)
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

