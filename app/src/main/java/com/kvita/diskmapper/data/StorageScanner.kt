package com.kvita.diskmapper.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.yield
import java.io.File

class StorageScanner {
    private val clusterSizeBytes = 4096L

    suspend fun scan(
        context: Context,
        rootUri: Uri,
        onProgress: ((Long) -> Unit)? = null
    ): ScanResult {
        val root = DocumentFile.fromTreeUri(context, rootUri)
            ?: return ScanResult(emptyList(), 0L, 0L, 0L)

        var visited = 0L
        val items = mutableListOf<StorageItem>()

        suspend fun walk(node: DocumentFile): SizePair {
            visited++
            if (visited % 100L == 0L) {
                onProgress?.invoke(visited)
                yield()
            }

            if (!node.isDirectory) {
                val logicalSize = node.length().coerceAtLeast(0L)
                val onDiskSize = estimateOnDiskBytes(logicalSize)
                items += StorageItem(
                    uri = node.uri,
                    absolutePath = null,
                    name = node.name ?: "(unknown)",
                    logicalSizeBytes = logicalSize,
                    onDiskSizeBytes = onDiskSize,
                    isDirectory = false,
                    mimeType = node.type
                )
                return SizePair(logicalSize, onDiskSize)
            }

            var logicalTotal = 0L
            var onDiskTotal = 0L
            val children = runCatching { node.listFiles() }.getOrDefault(emptyArray())
            for (child in children) {
                val childSizes = walk(child)
                logicalTotal += childSizes.logicalBytes
                onDiskTotal += childSizes.onDiskBytes
            }

            if (node.uri != rootUri) {
                items += StorageItem(
                    uri = node.uri,
                    absolutePath = null,
                    name = node.name ?: "(folder)",
                    logicalSizeBytes = logicalTotal,
                    onDiskSizeBytes = onDiskTotal,
                    isDirectory = true,
                    mimeType = node.type
                )
            }
            return SizePair(logicalTotal, onDiskTotal)
        }

        val totalSize = walk(root)
        onProgress?.invoke(visited)

        return ScanResult(
            items = items.sortedByDescending { it.onDiskSizeBytes },
            visitedNodes = visited,
            rootLogicalSizeBytes = totalSize.logicalBytes,
            rootOnDiskSizeBytes = totalSize.onDiskBytes
        )
    }

    suspend fun scanFileTree(
        root: File,
        onProgress: ((Long) -> Unit)? = null
    ): ScanResult {
        if (!root.exists() || !root.isDirectory) return ScanResult(emptyList(), 0L, 0L, 0L)

        var visited = 0L
        val items = mutableListOf<StorageItem>()

        suspend fun walk(node: File): SizePair {
            visited++
            if (visited % 100L == 0L) {
                onProgress?.invoke(visited)
                yield()
            }

            if (node.isFile) {
                val logicalSize = node.length().coerceAtLeast(0L)
                val onDiskSize = estimateOnDiskBytes(logicalSize)
                items += StorageItem(
                    uri = Uri.fromFile(node),
                    absolutePath = node.absolutePath,
                    name = node.name,
                    logicalSizeBytes = logicalSize,
                    onDiskSizeBytes = onDiskSize,
                    isDirectory = false,
                    mimeType = null
                )
                return SizePair(logicalSize, onDiskSize)
            }

            var logicalTotal = 0L
            var onDiskTotal = 0L
            val children = runCatching { node.listFiles() }.getOrNull() ?: emptyArray()
            for (child in children) {
                val childSizes = walk(child)
                logicalTotal += childSizes.logicalBytes
                onDiskTotal += childSizes.onDiskBytes
            }

            if (node.absolutePath != root.absolutePath) {
                items += StorageItem(
                    uri = Uri.fromFile(node),
                    absolutePath = node.absolutePath,
                    name = node.name.ifEmpty { "(folder)" },
                    logicalSizeBytes = logicalTotal,
                    onDiskSizeBytes = onDiskTotal,
                    isDirectory = true,
                    mimeType = null
                )
            }
            return SizePair(logicalTotal, onDiskTotal)
        }

        val totalSize = walk(root)
        onProgress?.invoke(visited)

        return ScanResult(
            items = items.sortedByDescending { it.onDiskSizeBytes },
            visitedNodes = visited,
            rootLogicalSizeBytes = totalSize.logicalBytes,
            rootOnDiskSizeBytes = totalSize.onDiskBytes
        )
    }

    fun delete(context: Context, itemUri: Uri): Boolean {
        val doc = DocumentFile.fromSingleUri(context, itemUri)
            ?: DocumentFile.fromTreeUri(context, itemUri)
            ?: return false
        return runCatching { doc.delete() }.getOrDefault(false)
    }

    fun deleteFile(itemPath: String): Boolean {
        val file = File(itemPath)
        return runCatching {
            if (file.isDirectory) false else file.delete()
        }.getOrDefault(false)
    }

    private fun estimateOnDiskBytes(logicalBytes: Long): Long {
        if (logicalBytes <= 0) return 0L
        val chunks = (logicalBytes + clusterSizeBytes - 1) / clusterSizeBytes
        return chunks * clusterSizeBytes
    }

    private data class SizePair(
        val logicalBytes: Long,
        val onDiskBytes: Long
    )
}

