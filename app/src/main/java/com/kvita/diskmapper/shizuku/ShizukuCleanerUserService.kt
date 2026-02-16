package com.kvita.diskmapper.shizuku

import android.os.Process
import java.io.File
import java.util.Locale

class ShizukuCleanerUserService : IShizukuCleanerService.Stub() {
    private val clusterSizeBytes = 4096L
    private val fieldSeparator = '\u001F'

    /**
     * Scans Android/data and Android/obb.
     *
     * Strategy: lightweight recursive size aggregation without storing every
     * file as a Record. Only directories (up to [MAX_DEPTH] levels inside each
     * target) are emitted. This keeps memory usage low so lmkd does not kill
     * the Shizuku user-service process, and the Binder payload stays well
     * under the ~1 MB transaction limit.
     */
    override fun scanPaths(basePath: String, telegramOnly: Boolean, maxItems: Int): String {
        val targets = resolveAndroidTargets(basePath)
        if (targets.isEmpty()) return ""

        val records = ArrayList<Record>(2048)
        for (target in targets) {
            val canList = runCatching { target.listFiles() }.getOrNull()
            if (canList != null) {
                walkLight(target, target, telegramOnly, 0, records)
            } else {
                // Cannot list — fall back to `du`
                val duBytes = readDuBytes(target.absolutePath) ?: 0L
                records += Record(
                    path = target.absolutePath,
                    name = target.name.ifEmpty { "(folder)" },
                    logicalBytes = duBytes,
                    onDiskBytes = duBytes,
                    isDirectory = true
                )
            }
        }

        val sorted = records.sortedByDescending { it.onDiskBytes }
        val capped = if (maxItems > 0) sorted.take(maxItems) else sorted
        return buildPayload(capped)
    }

    /**
     * Recursively compute sizes but only emit directory Records up to
     * [MAX_DEPTH] levels deep. Files are never stored — only their sizes
     * are accumulated into parent directory totals.
     */
    private fun walkLight(
        root: File,
        current: File,
        telegramOnly: Boolean,
        depth: Int,
        out: MutableList<Record>
    ): SizePair {
        if (current.isFile) {
            val logical = current.length().coerceAtLeast(0L)
            return SizePair(logical, estimateOnDisk(logical))
        }

        val children = runCatching { current.listFiles() }.getOrNull() ?: emptyArray()
        var logicalTotal = 0L
        var onDiskTotal = 0L
        for (child in children) {
            val s = walkLight(root, child, telegramOnly, depth + 1, out)
            logicalTotal += s.logicalBytes
            onDiskTotal += s.onDiskBytes
        }

        // Emit this directory if it's not the target root itself
        if (current.absolutePath != root.absolutePath && depth <= MAX_DEPTH) {
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

    private fun buildPayload(records: List<Record>): String {
        return records.joinToString("\n") { record ->
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

    override fun diagnostics(): String {
        val targets = resolveAndroidTargets("/storage/emulated/0")
        val uid = Process.myUid()
        val data = targets.find { it.absolutePath.endsWith("/Android/data") }
        val obb = targets.find { it.absolutePath.endsWith("/Android/obb") }
        val dataEntries = data?.listFiles()?.size ?: -1
        val obbEntries = obb?.listFiles()?.size ?: -1
        val dataDuMb = (readDuBytes("/storage/emulated/0/Android/data") ?: -1L) / (1024L * 1024L)
        val obbDuMb = (readDuBytes("/storage/emulated/0/Android/obb") ?: -1L) / (1024L * 1024L)
        return "uid=$uid;dataEntries=$dataEntries;obbEntries=$obbEntries;duDataMb=$dataDuMb;duObbMb=$obbDuMb"
    }

    override fun diskStats(): String {
        return runCatching {
            val process = ProcessBuilder("sh", "-c", "dumpsys diskstats 2>/dev/null")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output
        }.getOrElse { "" }
    }

    private fun isTelegramPath(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        return lower.contains("telegram") ||
            lower.contains("org.telegram.messenger") ||
            lower.contains("org.telegram.plus")
    }

    private fun resolveAndroidTargets(basePath: String): List<File> {
        val candidates = linkedSetOf(
            "$basePath/Android/data",
            "$basePath/Android/obb",
            "/storage/emulated/0/Android/data",
            "/storage/emulated/0/Android/obb",
            "/storage/self/primary/Android/data",
            "/storage/self/primary/Android/obb",
            "/sdcard/Android/data",
            "/sdcard/Android/obb"
        )

        val valid = ArrayList<File>()
        for (path in candidates) {
            val file = File(path)
            if (!file.exists() || !file.isDirectory) continue
            valid += file
        }
        return valid.distinctBy { it.absolutePath }
    }

    private fun readDuBytes(path: String): Long? {
        val safePath = path.replace("\"", "\\\"")
        val command = "du -sk \"$safePath\" 2>/dev/null | head -n 1"
        return runCatching {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val line = process.inputStream.bufferedReader().use { it.readLine().orEmpty() }
            process.waitFor()
            val kb = line.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
                ?: return@runCatching null
            kb * 1024L
        }.getOrNull()
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

    companion object {
        /** Max depth of subdirectories to emit (relative to each target root).
         *  depth 0 = Android/data/com.app
         *  depth 1 = Android/data/com.app/files
         *  depth 2 = Android/data/com.app/files/documents  */
        private const val MAX_DEPTH = 4
    }
}
