package com.kvita.diskmapper.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kvita.diskmapper.data.StorageItem
import java.util.Locale

enum class FileFilter {
    ALL, TELEGRAM, VIDEOS, ARCHIVES, INSTALLERS
}

enum class ViewMode {
    TREE, LIST
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiskMapperScreen(vm: DiskMapperViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var filter by remember { mutableStateOf(FileFilter.ALL) }
    var viewMode by remember { mutableStateOf(ViewMode.TREE) }
    var pendingDelete by remember { mutableStateOf<StorageItem?>(null) }
    val expandedMap = remember { mutableStateMapOf<String, Boolean>() }

    val folderPicker = rememberLauncherForActivityResult(OpenDocumentTree()) { uri: Uri? ->
        uri?.let { vm.selectFolder(context, it) }
    }

    LaunchedEffect(Unit) {
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

    val treeRoots = remember(filteredItems) {
        buildTree(filteredItems)
    }
    val treeRows = remember(treeRoots, expandedMap) {
        flattenTree(treeRoots, expandedMap)
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { folderPicker.launch(null) }, enabled = !state.isScanning) {
                    Text(if (state.selectedFolderUri == null) "Select folder" else "Change folder")
                }
                Button(
                    onClick = {
                        if (hasAllFilesAccess()) {
                            vm.selectAllFilesRoot("/storage/emulated/0", context)
                        } else {
                            requestAllFilesAccess(context)
                        }
                    },
                    enabled = !state.isScanning
                ) {
                    Text("Root scan")
                }
                Button(
                    onClick = {
                        val telegramOnly = filter == FileFilter.TELEGRAM
                        vm.scanAndroidPrivateWithShizuku(context, telegramOnly)
                    },
                    enabled = !state.isScanning
                ) {
                    Text("Shizuku Android/")
                }
                if (state.selectedFolderUri != null) {
                    Column(modifier = Modifier.align(Alignment.CenterVertically)) {
                        Text(
                            text = "Logical: ${formatBytes(state.rootLogicalSizeBytes)}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "On-disk est: ${formatBytes(state.rootOnDiskSizeBytes)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (state.selectedRootPath != null) {
                    Column(modifier = Modifier.align(Alignment.CenterVertically)) {
                        Text(
                            text = "Root: ${state.selectedRootPath}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (!state.shizukuDiagnostics.isNullOrBlank()) {
                            Text(
                                text = "Shizuku: ${state.shizukuDiagnostics}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("Tree", viewMode == ViewMode.TREE) { viewMode = ViewMode.TREE }
                FilterChip("List", viewMode == ViewMode.LIST) { viewMode = ViewMode.LIST }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("All", filter == FileFilter.ALL) { filter = FileFilter.ALL }
                FilterChip("Telegram", filter == FileFilter.TELEGRAM) { filter = FileFilter.TELEGRAM }
                FilterChip("Videos", filter == FileFilter.VIDEOS) { filter = FileFilter.VIDEOS }
                FilterChip("Archives", filter == FileFilter.ARCHIVES) { filter = FileFilter.ARCHIVES }
                FilterChip("Installers", filter == FileFilter.INSTALLERS) { filter = FileFilter.INSTALLERS }
            }

            if (state.isScanning) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text("Scanning... nodes: ${state.visitedNodes}")
                }
            }

            if (viewMode == ViewMode.TREE && treeRows.isNotEmpty()) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(treeRows.take(500), key = { it.node.path }) { row ->
                        ItemCard(
                            item = row.node.item,
                            depth = row.depth,
                            canExpand = row.node.children.isNotEmpty(),
                            expanded = expandedMap[row.node.path] ?: false,
                            onToggleExpand = {
                                val current = expandedMap[row.node.path] ?: false
                                expandedMap[row.node.path] = !current
                            },
                            onDelete = { pendingDelete = row.node.item }
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filteredItems.take(300), key = { it.uri.toString() }) { item ->
                        ItemCard(
                            item = item,
                            depth = 0,
                            canExpand = false,
                            expanded = false,
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
                    pendingDelete?.let { vm.deleteItem(context, it) }
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun FilterChip(title: String, selected: Boolean, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(title) }, leadingIcon = if (selected) ({ Text("*") }) else null)
}

@Composable
private fun ItemCard(
    item: StorageItem,
    depth: Int,
    canExpand: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = (depth * 12).dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (canExpand) {
                    TextButton(onClick = onToggleExpand) {
                        Text(if (expanded) "-" else "+")
                    }
                }
                Icon(
                    if (item.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                    contentDescription = null
                )
                Column {
                    Text(item.name, maxLines = 1)
                    Text(
                        text = "Logical: ${formatBytes(item.logicalSizeBytes)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "On-disk est: ${formatBytes(item.onDiskSizeBytes)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (!item.isDirectory) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
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
    val item: StorageItem,
    val children: MutableList<TreeNode> = mutableListOf()
)

private data class TreeRow(
    val node: TreeNode,
    val depth: Int
)

private fun buildTree(items: List<StorageItem>): List<TreeNode> {
    val pathItems = items.filter { !it.absolutePath.isNullOrBlank() }
    if (pathItems.isEmpty()) return emptyList()

    val byPath = pathItems.associateBy { it.absolutePath!! }.toMutableMap()
    val nodes = byPath.mapValues { (path, item) -> TreeNode(path = path, item = item) }.toMutableMap()
    val roots = mutableListOf<TreeNode>()

    for ((path, node) in nodes) {
        val parentPath = parentPath(path)
        val parent = if (parentPath == null) null else nodes[parentPath]
        if (parent != null) {
            parent.children += node
        } else {
            roots += node
        }
    }

    roots.forEach { sortNode(it) }
    return roots.sortedByDescending { it.item.onDiskSizeBytes }
}

private fun flattenTree(roots: List<TreeNode>, expanded: Map<String, Boolean>): List<TreeRow> {
    val out = mutableListOf<TreeRow>()
    for (root in roots.sortedByDescending { it.item.onDiskSizeBytes }) {
        appendNode(root, 0, expanded, out)
    }
    return out
}

private fun appendNode(
    node: TreeNode,
    depth: Int,
    expanded: Map<String, Boolean>,
    out: MutableList<TreeRow>
) {
    out += TreeRow(node, depth)
    if (expanded[node.path] == true) {
        for (child in node.children.sortedByDescending { it.item.onDiskSizeBytes }) {
            appendNode(child, depth + 1, expanded, out)
        }
    }
}

private fun sortNode(node: TreeNode) {
    node.children.sortByDescending { it.item.onDiskSizeBytes }
    node.children.forEach { sortNode(it) }
}

private fun parentPath(path: String): String? {
    val normalized = path.trimEnd('/', '\\')
    val idx = normalized.lastIndexOfAny(charArrayOf('/', '\\'))
    if (idx <= 0) return null
    return normalized.substring(0, idx)
}

