package com.kvita.diskmapper.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.yield

class StorageScanner {
    suspend fun scan(
        context: Context,
        rootUri: Uri,
        onProgress: ((Long) -> Unit)? = null
    ): ScanResult {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return ScanResult(emptyList(), 0L, 0L)

        var visited = 0L
        val items = mutableListOf<StorageItem>()

        suspend fun walk(node: DocumentFile): Long {
            visited++
            if (visited % 100L == 0L) {
                onProgress?.invoke(visited)
                yield()
            }

            if (!node.isDirectory) {
                val size = node.length().coerceAtLeast(0L)
                items += StorageItem(
                    uri = node.uri,
                    name = node.name ?: "(unknown)",
                    sizeBytes = size,
                    isDirectory = false,
                    mimeType = node.type
                )
                return size
            }

            var total = 0L
            val children = runCatching { node.listFiles() }.getOrDefault(emptyArray())
            for (child in children) {
                total += walk(child)
            }

            if (node.uri != rootUri) {
                items += StorageItem(
                    uri = node.uri,
                    name = node.name ?: "(folder)",
                    sizeBytes = total,
                    isDirectory = true,
                    mimeType = node.type
                )
            }
            return total
        }

        val totalSize = walk(root)
        onProgress?.invoke(visited)

        return ScanResult(
            items = items.sortedByDescending { it.sizeBytes },
            visitedNodes = visited,
            rootSizeBytes = totalSize
        )
    }

    fun delete(context: Context, itemUri: Uri): Boolean {
        val doc = DocumentFile.fromSingleUri(context, itemUri)
            ?: DocumentFile.fromTreeUri(context, itemUri)
            ?: return false
        return runCatching { doc.delete() }.getOrDefault(false)
    }
}

