package com.kvita.diskmapper.data

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.os.storage.StorageManager

/**
 * Uses Android's public [StorageStatsManager] API for both device categories
 * and per-app details. Cache is a subset of dataBytes, so exposed data values
 * exclude cache to keep every displayed category non-overlapping.
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

    /** Non-overlapping category-level storage totals. */
    data class CategoryBreakdown(
        val appSize: Long = 0,
        val appDataSize: Long = 0,
        val appCacheSize: Long = 0,
        val photosSize: Long = 0,
        val videosSize: Long = 0,
        val audioSize: Long = 0,
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

    internal data class RawCategoryStats(
        val appBytes: Long,
        val dataBytesIncludingCache: Long,
        val cacheBytes: Long,
        val externalTotalBytes: Long,
        val externalAppBytes: Long,
        val imageBytes: Long,
        val videoBytes: Long,
        val audioBytes: Long,
        val totalCapacity: Long,
        val totalFree: Long
    )

    fun queryFull(context: Context): FullStorageInfo {
        if (!hasUsageAccess(context)) {
            throw SecurityException("Usage access is required")
        }
        return FullStorageInfo(
            categories = queryCategoryBreakdown(context),
            apps = queryPerApp(context)
        )
    }

    @Suppress("DEPRECATION")
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun queryCategoryBreakdown(context: Context): CategoryBreakdown {
        val manager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
            ?: return CategoryBreakdown()
        val uuid = StorageManager.UUID_DEFAULT
        val user = Process.myUserHandle()
        val appStats = manager.queryStatsForUser(uuid, user)
        val external = manager.queryExternalStatsForUser(uuid, user)
        val total = manager.getTotalBytes(uuid)
        val free = manager.getFreeBytes(uuid)
        return calculateCategoryBreakdown(
            RawCategoryStats(
                appBytes = appStats.appBytes,
                dataBytesIncludingCache = appStats.dataBytes,
                cacheBytes = appStats.cacheBytes,
                externalTotalBytes = external.totalBytes,
                externalAppBytes = external.appBytes,
                imageBytes = external.imageBytes,
                videoBytes = external.videoBytes,
                audioBytes = external.audioBytes,
                totalCapacity = total,
                totalFree = free
            )
        )
    }

    internal fun calculateCategoryBreakdown(raw: RawCategoryStats): CategoryBreakdown {
        val app = raw.appBytes.coerceAtLeast(0L)
        val cache = raw.cacheBytes.coerceAtLeast(0L)
        val dataWithoutCache = (raw.dataBytesIncludingCache - cache).coerceAtLeast(0L)
        val images = raw.imageBytes.coerceAtLeast(0L)
        val videos = raw.videoBytes.coerceAtLeast(0L)
        val audio = raw.audioBytes.coerceAtLeast(0L)
        val sharedOther = (
            // External app bytes are already included in per-app StorageStats.
            // Subtract them here so shared storage is not counted twice.
            raw.externalTotalBytes.coerceAtLeast(0L) - raw.externalAppBytes.coerceAtLeast(0L) -
                images - videos - audio
            ).coerceAtLeast(0L)
        val accounted = app + dataWithoutCache + cache + images + videos + audio + sharedOther

        // StorageStatsManager.getFreeBytes() reports reclaimable cache as free
        // space, while `accounted` counts that cache as used. Add it back so
        // used/free match what df reports and System stays a real remainder.
        // Only the part that actually fits inside the reported free space is
        // reclaimable, so adding more than that would invent used bytes.
        val capacity = raw.totalCapacity.coerceAtLeast(0L)
        val reportedFree = raw.totalFree.coerceAtLeast(0L).coerceAtMost(capacity)
        val reclaimableCache = cache.coerceAtMost(reportedFree)
        // Measured categories win over the derived figure: if they already
        // exceed it, `used` follows them so the parts always sum to the whole.
        // Free absorbs the difference; only contradictory platform data (parts
        // larger than the disk) can push used past capacity, and reporting that
        // beats silently showing a breakdown that does not add up.
        val used = maxOf(capacity - reportedFree + reclaimableCache, accounted)
            .coerceAtLeast(0L)
        val free = (capacity - used).coerceAtLeast(0L)

        return CategoryBreakdown(
            appSize = app,
            appDataSize = dataWithoutCache,
            appCacheSize = cache,
            photosSize = images,
            videosSize = videos,
            audioSize = audio,
            systemSize = (used - accounted).coerceAtLeast(0L),
            otherSize = sharedOther,
            totalCapacity = capacity,
            totalFree = free,
            totalUsed = used
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
                val cacheBytes = stats.cacheBytes
                val dataBytes = (stats.dataBytes - cacheBytes).coerceAtLeast(0L)
                val total = appBytes + dataBytes + cacheBytes
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
            val appLabel = "${app.label} (${app.packageName})"
            items += StorageItem(
                uri = Uri.parse("package://${app.packageName}"),
                absolutePath = appPath,
                name = appLabel,
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

        // Category-focused per-app trees for quick top offenders.
        for (app in info.apps) {
            val appLabel = "${app.label} (${app.packageName})"
            if (app.appBytes > 0) {
                items += StorageItem(
                    uri = Uri.parse("apps-apk://${app.packageName}"),
                    absolutePath = "/storage-map/apps-apk-by-app/${app.packageName}",
                    name = appLabel,
                    logicalSizeBytes = app.appBytes,
                    onDiskSizeBytes = app.appBytes,
                    isDirectory = false,
                    mimeType = null
                )
            }
            if (app.dataBytes > 0) {
                items += StorageItem(
                    uri = Uri.parse("apps-data://${app.packageName}"),
                    absolutePath = "/storage-map/apps-data-by-app/${app.packageName}",
                    name = appLabel,
                    logicalSizeBytes = app.dataBytes,
                    onDiskSizeBytes = app.dataBytes,
                    isDirectory = false,
                    mimeType = null
                )
            }
            if (app.cacheBytes > 0) {
                items += StorageItem(
                    uri = Uri.parse("apps-cache://${app.packageName}"),
                    absolutePath = "/storage-map/apps-cache-by-app/${app.packageName}",
                    name = appLabel,
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
