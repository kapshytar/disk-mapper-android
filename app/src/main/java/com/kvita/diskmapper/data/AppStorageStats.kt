package com.kvita.diskmapper.data

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.storage.StorageManager
import com.kvita.diskmapper.ui.UiTrace

/**
 * Combines two data sources to show complete storage picture:
 *
 * 1. **Category totals** via `dumpsys diskstats` — gives accurate system-level
 *    breakdown (Apps 32GB, App Data 25GB, Photos 2GB, System 20GB, etc.)
 *
 * 2. **Per-app breakdown** via [StorageStatsManager] — shows individual app
 *    sizes (APK + data + cache) for apps accessible to the current user.
 */
object AppStorageStats {

    data class AppUsage(
        val packageName: String,
        val label: String,
        val appBytes: Long,
        val dataBytes: Long,
        val cacheBytes: Long,
        val totalBytes: Long
    )

    /** Category-level storage totals from dumpsys diskstats. */
    data class CategoryBreakdown(
        val appSize: Long = 0,
        val appDataSize: Long = 0,
        val appCacheSize: Long = 0,
        val photosSize: Long = 0,
        val videosSize: Long = 0,
        val audioSize: Long = 0,
        val downloadsSize: Long = 0,
        val systemSize: Long = 0,
        val otherSize: Long = 0,
        val totalUsed: Long = 0,
        val totalCapacity: Long = 0,
        val totalFree: Long = 0
    )

    data class FullStorageInfo(
        val categories: CategoryBreakdown,
        val apps: List<AppUsage>
    )

    fun queryFull(context: Context, diskStatsRaw: String? = null): FullStorageInfo {
        val categories = if (!diskStatsRaw.isNullOrBlank()) {
            parseDiskStats(diskStatsRaw)
        } else {
            queryCategoryBreakdown()
        }
        val apps = queryPerApp(context)
        return FullStorageInfo(categories, apps)
    }

    /**
     * Parse `dumpsys diskstats` for category-level totals.
     * No special permissions needed.
     */
    private fun queryCategoryBreakdown(): CategoryBreakdown {
        return try {
            val process = ProcessBuilder("sh", "-c", "dumpsys diskstats 2>/dev/null")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            parseDiskStats(output)
        } catch (e: Exception) {
            UiTrace.error("queryCategoryBreakdown failed", e)
            CategoryBreakdown()
        }
    }

    internal fun parseDiskStats(output: String): CategoryBreakdown {
        fun extract(key: String): Long {
            val regex = Regex("$key:\\s*(\\d+)")
            return regex.find(output)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        }
        val dataFreeMatch = Regex("Data-Free:\\s*(\\d+)K\\s*/\\s*(\\d+)K").find(output)
        val freeK = dataFreeMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val totalK = dataFreeMatch?.groupValues?.get(2)?.toLongOrNull() ?: 0L

        return CategoryBreakdown(
            appSize = extract("App Size"),
            appDataSize = extract("App Data Size"),
            appCacheSize = extract("App Cache Size"),
            photosSize = extract("Photos Size"),
            videosSize = extract("Videos Size"),
            audioSize = extract("Audio Size"),
            downloadsSize = extract("Downloads Size"),
            systemSize = extract("System Size"),
            otherSize = extract("Other Size"),
            totalCapacity = totalK * 1024L,
            totalFree = freeK * 1024L,
            totalUsed = (totalK - freeK) * 1024L
        )
    }

    private fun queryPerApp(context: Context): List<AppUsage> {
        val ssm = context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
            ?: return emptyList()
        val pm = context.packageManager
        val uuid = StorageManager.UUID_DEFAULT
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val result = ArrayList<AppUsage>(apps.size)

        for (app in apps) {
            try {
                val stats = ssm.queryStatsForPackage(uuid, app.packageName, android.os.Process.myUserHandle())
                val appBytes = stats.appBytes
                val dataBytes = stats.dataBytes
                val cacheBytes = stats.cacheBytes
                val total = appBytes + dataBytes
                if (total <= 0) continue
                val label = try {
                    pm.getApplicationLabel(app).toString()
                } catch (_: Exception) {
                    app.packageName
                }
                result += AppUsage(app.packageName, label, appBytes, dataBytes, cacheBytes, total)
            } catch (_: Exception) {
                // skip silently
            }
        }
        result.sortByDescending { it.totalBytes }
        return result
    }

    /** Convert full info to StorageItems for tree UI. */
    fun toStorageItems(info: FullStorageInfo): List<StorageItem> {
        val items = ArrayList<StorageItem>()
        val cat = info.categories

        fun addCategory(name: String, bytes: Long, path: String) {
            if (bytes <= 0) return
            items += StorageItem(
                uri = Uri.parse("category://$path"),
                absolutePath = "/storage-map/categories/$path",
                name = name,
                logicalSizeBytes = bytes,
                onDiskSizeBytes = bytes,
                isDirectory = true,
                mimeType = null
            )
        }

        addCategory("Apps (APK)", cat.appSize, "apps-apk")
        addCategory("App Data", cat.appDataSize, "apps-data")
        addCategory("App Cache", cat.appCacheSize, "apps-cache")
        addCategory("Photos", cat.photosSize, "photos")
        addCategory("Videos", cat.videosSize, "videos")
        addCategory("Audio", cat.audioSize, "audio")
        addCategory("Downloads", cat.downloadsSize, "downloads")
        addCategory("System", cat.systemSize, "system")
        addCategory("Other", cat.otherSize, "other")

        if (cat.totalFree > 0) {
            items += StorageItem(
                uri = Uri.parse("category://free"),
                absolutePath = "/storage-map/free",
                name = "Free space",
                logicalSizeBytes = cat.totalFree,
                onDiskSizeBytes = cat.totalFree,
                isDirectory = false,
                mimeType = null
            )
        }

        // Per-app detail items
        for (app in info.apps) {
            val appPath = "/storage-map/per-app/${app.packageName}"
            items += StorageItem(
                uri = Uri.parse("package://${app.packageName}"),
                absolutePath = appPath,
                name = app.label,
                logicalSizeBytes = app.totalBytes,
                onDiskSizeBytes = app.totalBytes,
                isDirectory = true,
                mimeType = null
            )
            if (app.appBytes > 0) {
                items += StorageItem(
                    uri = Uri.parse("package://${app.packageName}/apk"),
                    absolutePath = "$appPath/apk",
                    name = "APK",
                    logicalSizeBytes = app.appBytes,
                    onDiskSizeBytes = app.appBytes,
                    isDirectory = false,
                    mimeType = null
                )
            }
            if (app.dataBytes > 0) {
                items += StorageItem(
                    uri = Uri.parse("package://${app.packageName}/data"),
                    absolutePath = "$appPath/data",
                    name = "Data",
                    logicalSizeBytes = app.dataBytes,
                    onDiskSizeBytes = app.dataBytes,
                    isDirectory = false,
                    mimeType = null
                )
            }
            if (app.cacheBytes > 0) {
                items += StorageItem(
                    uri = Uri.parse("package://${app.packageName}/cache"),
                    absolutePath = "$appPath/cache",
                    name = "Cache",
                    logicalSizeBytes = app.cacheBytes,
                    onDiskSizeBytes = app.cacheBytes,
                    isDirectory = false,
                    mimeType = null
                )
            }
        }

        return items
    }
}
