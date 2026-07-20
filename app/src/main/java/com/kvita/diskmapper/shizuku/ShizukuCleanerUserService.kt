package com.kvita.diskmapper.shizuku

import android.os.Process
import com.kvita.diskmapper.data.AndroidPrivateAccounting
import java.io.File
import java.util.Locale
import java.util.PriorityQueue

class ShizukuCleanerUserService : IShizukuCleanerService.Stub() {
    private val clusterSizeBytes = 4096L
    private val fieldSeparator = '\u001F'

    /**
     * Scans Android/data and Android/obb.
     *
     * Strategy: lightweight recursive size aggregation without storing every
     * file as a Record. Directory records and largest file records are bounded
     * before serialization. This keeps memory usage low so lmkd does not kill
     * the Shizuku user-service process, and the Binder payload stays bounded.
     */
    override fun scanPaths(basePath: String, telegramOnly: Boolean, maxItems: Int): String {
        val targets = resolveAndroidTargets(basePath)
        if (targets.isEmpty()) return ""

        val largestDirectories = PriorityQueue<Record>(compareBy { it.onDiskBytes })
        val rootRecords = ArrayList<Record>(2)
        val largestFiles = PriorityQueue<Record>(compareBy { it.onDiskBytes })
        val fileLimit = if (maxItems > 0) (maxItems / 3).coerceIn(100, 1500) else 1500
        val directoryLimit = if (maxItems > 0) maxItems.coerceAtLeast(100) else 5000
        for (target in targets) {
            val canList = runCatching { target.listFiles() }.getOrNull()
            if (canList != null) {
                val totals = walkLight(
                    root = target,
                    rootCanonicalPath = runCatching { target.canonicalPath }
                        .getOrDefault(target.absolutePath),
                    current = target,
                    telegramOnly = telegramOnly,
                    depth = 0,
                    largestDirectories = largestDirectories,
                    directoryLimit = directoryLimit,
                    largestFiles = largestFiles,
                    fileLimit = fileLimit,
                    visitedDirectories = hashSetOf()
                )
                rootRecords += Record(
                    path = target.absolutePath,
                    name = target.name.ifEmpty { "(folder)" },
                    logicalBytes = totals.logicalBytes,
                    onDiskBytes = totals.onDiskBytes,
                    isDirectory = true
                )
            } else {
                // Cannot list — fall back to `du`
                val duBytes = readDuBytes(target.absolutePath) ?: 0L
                rootRecords += Record(
                    path = target.absolutePath,
                    name = target.name.ifEmpty { "(folder)" },
                    logicalBytes = duBytes,
                    onDiskBytes = duBytes,
                    isDirectory = true
                )
            }
        }

        val details = (largestDirectories.toList() + largestFiles.toList())
            .sortedByDescending { it.onDiskBytes }
        val detailLimit = if (maxItems > 0) (maxItems - rootRecords.size).coerceAtLeast(0) else details.size
        return buildPayload((rootRecords + details.take(detailLimit)).sortedByDescending { it.onDiskBytes })
    }

    /**
     * Recursively compute full subtree sizes. Directory rows are emitted up to
     * [MAX_DEPTH], and only a bounded set of the largest file rows is retained.
     */
    private fun walkLight(
        root: File,
        rootCanonicalPath: String,
        current: File,
        telegramOnly: Boolean,
        depth: Int,
        largestDirectories: PriorityQueue<Record>,
        directoryLimit: Int,
        largestFiles: PriorityQueue<Record>,
        fileLimit: Int,
        visitedDirectories: MutableSet<String>
    ): SizePair {
        val canonicalPath = runCatching { current.canonicalPath }.getOrNull()
            ?: return SizePair(0L, 0L)
        if (canonicalPath != rootCanonicalPath && !canonicalPath.startsWith("$rootCanonicalPath/")) {
            return SizePair(0L, 0L)
        }

        if (current.isFile) {
            val logical = current.length().coerceAtLeast(0L)
            val onDisk = estimateOnDisk(logical)
            val included = !telegramOnly || isTelegramPath(current.absolutePath)
            if (included) {
                offerLargest(
                    largestFiles,
                    Record(
                        path = current.absolutePath,
                        name = current.name.ifEmpty { "(file)" },
                        logicalBytes = logical,
                        onDiskBytes = onDisk,
                        isDirectory = false
                    ),
                    fileLimit
                )
            }
            return SizePair(logical, onDisk)
        }

        if (!visitedDirectories.add(canonicalPath)) return SizePair(0L, 0L)
        if (depth >= MAX_SCAN_DEPTH) {
            val bytes = readDuBytes(current.absolutePath) ?: 0L
            return SizePair(bytes, bytes)
        }

        val children = runCatching { current.listFiles() }.getOrNull() ?: emptyArray()
        var logicalTotal = 0L
        var onDiskTotal = 0L
        for (child in children) {
            val s = walkLight(
                root,
                rootCanonicalPath,
                child,
                telegramOnly,
                depth + 1,
                largestDirectories,
                directoryLimit,
                largestFiles,
                fileLimit,
                visitedDirectories
            )
            logicalTotal += s.logicalBytes
            onDiskTotal += s.onDiskBytes
        }

        // Emit this directory if it's not the target root itself
        if (current.absolutePath != root.absolutePath && depth <= MAX_DEPTH) {
            if (!telegramOnly || isTelegramPath(current.absolutePath)) {
                offerLargest(
                    largestDirectories,
                    Record(
                        path = current.absolutePath,
                        name = current.name.ifEmpty { "(folder)" },
                        logicalBytes = logicalTotal,
                        onDiskBytes = onDiskTotal,
                        isDirectory = true
                    ),
                    directoryLimit
                )
            }
        }

        return SizePair(logicalTotal, onDiskTotal)
    }

    private fun offerLargest(
        records: PriorityQueue<Record>,
        candidate: Record,
        limit: Int
    ) {
        if (limit <= 0) return
        if (records.size < limit) {
            records += candidate
        } else if (candidate.onDiskBytes > (records.peek()?.onDiskBytes ?: Long.MAX_VALUE)) {
            records.poll()
            records += candidate
        }
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
            val file = File(absolutePath).canonicalFile
            if (!AndroidPrivateAccounting.isPrivatePath(file.absolutePath)) {
                return@runCatching false
            }
            if (!file.exists()) return@runCatching false
            if (file.isDirectory) return@runCatching false
            file.delete()
        }.getOrDefault(false)
    }

    override fun diagnostics(basePath: String): String {
        val targets = resolveAndroidTargets(basePath)
        val uid = Process.myUid()
        val data = targets.find { it.absolutePath.endsWith("/Android/data") }
        val obb = targets.find { it.absolutePath.endsWith("/Android/obb") }
        val dataEntries = data?.listFiles()?.size ?: -1
        val obbEntries = obb?.listFiles()?.size ?: -1
        val dataDuMb = (readDuBytes("$basePath/Android/data") ?: -1L) / (1024L * 1024L)
        val obbDuMb = (readDuBytes("$basePath/Android/obb") ?: -1L) / (1024L * 1024L)
        return "uid=$uid;dataEntries=$dataEntries;obbEntries=$obbEntries;duDataMb=$dataDuMb;duObbMb=$obbDuMb"
    }

    /**
     * Clears cache of ALL apps via `pm trim-caches`. Safe: apps rebuild caches
     * on demand. Needs shell (Shizuku) privileges — normal apps cannot call it.
     *
     * Returns "ok;freedBytes=N;freeAfter=M" or "err;<message>".
     */
    override fun trimCaches(): String {
        return runCatching {
            val before = dataFreeBytes()
            val process = ProcessBuilder("sh", "-c", "pm trim-caches 999G 2>&1")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            val after = dataFreeBytes()
            if (exit != 0) {
                "err;pm trim-caches exit=$exit ${output.take(200).trim()}"
            } else {
                "ok;freedBytes=${(after - before).coerceAtLeast(0L)};freeAfter=$after"
            }
        }.getOrElse { "err;${it.message}" }
    }

    private fun dataFreeBytes(): Long {
        return runCatching { android.os.StatFs("/data").let { it.availableBlocksLong * it.blockSizeLong } }
            .getOrDefault(0L)
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
        return valid.distinctBy {
            runCatching { it.canonicalPath }.getOrDefault(it.absolutePath)
        }
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
        private const val MAX_SCAN_DEPTH = 128
    }
}
