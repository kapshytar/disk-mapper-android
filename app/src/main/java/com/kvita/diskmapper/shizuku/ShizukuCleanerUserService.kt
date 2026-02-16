package com.kvita.diskmapper.shizuku

import java.io.File
import java.util.Locale

class ShizukuCleanerUserService : IShizukuCleanerService.Stub() {
    private val clusterSizeBytes = 4096L
    private val fieldSeparator = '\u001F'

    override fun scanPaths(basePath: String, telegramOnly: Boolean, maxItems: Int): String {
        val root = File(basePath)
        if (!root.exists()) return ""

        val targets = listOf(
            File(root, "Android/data"),
            File(root, "Android/obb")
        ).filter { it.exists() }

        val items = ArrayList<Record>()
        for (target in targets) {
            walk(target, target, telegramOnly, items)
        }

        val sorted = items.sortedByDescending { it.onDiskBytes }
        val capped = if (maxItems > 0) sorted.take(maxItems) else sorted
        return capped.joinToString("\n") { record ->
            listOf(
                record.path,
                record.name,
                record.logicalBytes.toString(),
                record.onDiskBytes.toString(),
                if (record.isDirectory) "1" else "0"
            ).joinToString(fieldSeparator.toString())
        }
    }

    override fun deleteFile(absolutePath: String): Boolean {
        return runCatching {
            val file = File(absolutePath)
            if (!file.exists()) return@runCatching false
            if (file.isDirectory) return@runCatching false
            file.delete()
        }.getOrDefault(false)
    }

    private fun walk(root: File, current: File, telegramOnly: Boolean, out: MutableList<Record>): SizePair {
        if (current.isFile) {
            val logical = current.length().coerceAtLeast(0L)
            val onDisk = estimateOnDisk(logical)
            if (!telegramOnly || isTelegramPath(current.absolutePath)) {
                out += Record(
                    path = current.absolutePath,
                    name = current.name,
                    logicalBytes = logical,
                    onDiskBytes = onDisk,
                    isDirectory = false
                )
            }
            return SizePair(logical, onDisk)
        }

        val children = runCatching { current.listFiles() }.getOrNull() ?: emptyArray()
        var logicalTotal = 0L
        var onDiskTotal = 0L
        for (child in children) {
            val childSize = walk(root, child, telegramOnly, out)
            logicalTotal += childSize.logicalBytes
            onDiskTotal += childSize.onDiskBytes
        }

        if (current.absolutePath != root.absolutePath) {
            if (!telegramOnly || isTelegramPath(current.absolutePath)) {
                out += Record(
                    path = current.absolutePath,
                    name = current.name.ifEmpty { "(folder)" },
                    logicalBytes = logicalTotal,
                    onDiskBytes = onDiskTotal,
                    isDirectory = true
                )
            }
        }
        return SizePair(logicalTotal, onDiskTotal)
    }

    private fun isTelegramPath(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        return lower.contains("telegram") ||
            lower.contains("org.telegram.messenger") ||
            lower.contains("org.telegram.plus")
    }

    private fun estimateOnDisk(logicalBytes: Long): Long {
        if (logicalBytes <= 0L) return 0L
        val chunks = (logicalBytes + clusterSizeBytes - 1) / clusterSizeBytes
        return chunks * clusterSizeBytes
    }

    private data class Record(
        val path: String,
        val name: String,
        val logicalBytes: Long,
        val onDiskBytes: Long,
        val isDirectory: Boolean
    )

    private data class SizePair(
        val logicalBytes: Long,
        val onDiskBytes: Long
    )
}
