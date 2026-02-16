package com.kvita.diskmapper.data

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.storage.StorageManager
import com.kvita.diskmapper.ui.UiTrace
import java.util.UUID

/**
 * Uses [StorageStatsManager] (requires PACKAGE_USAGE_STATS) to enumerate
 * per-app storage: app size, data size, cache size.
 *
 * This covers the ~85 GB hidden in /data/app + /data/data that the file-tree
 * scan cannot see without root.
 */
object AppStorageStats {

    data class AppUsage(
        val packageName: String,
        val label: String,
        val appBytes: Long,      // APK + libs
        val dataBytes: Long,     // data + cache
        val cacheBytes: Long,    // cache subset
        val totalBytes: Long     // app + data
    )

    /**
     * Returns per-app storage usage sorted by total descending.
     * Requires the user to have granted "Usage access" in Settings.
     */
    fun queryAll(context: Context): List<AppUsage> {
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
                result += AppUsage(
                    packageName = app.packageName,
                    label = label,
                    appBytes = appBytes,
                    dataBytes = dataBytes,
                    cacheBytes = cacheBytes,
                    totalBytes = total
                )
            } catch (e: Exception) {
                // SecurityException if no usage access, or PackageManager.NameNotFoundException
                UiTrace.error("AppStorageStats skip ${app.packageName}", e)
            }
        }

        result.sortByDescending { it.totalBytes }
        return result
    }

    /** Convert [AppUsage] list to [StorageItem] list for the tree UI. */
    fun toStorageItems(apps: List<AppUsage>): List<StorageItem> {
        return apps.map { app ->
            StorageItem(
                uri = Uri.parse("package://${app.packageName}"),
                absolutePath = "/apps/${app.packageName}",
                name = "${app.label} (${app.packageName})",
                logicalSizeBytes = app.totalBytes,
                onDiskSizeBytes = app.totalBytes,
                isDirectory = true,
                mimeType = null
            )
        }
    }
}
