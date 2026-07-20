package com.kvita.diskmapper.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppStorageStatsTest {
    @Test
    fun categories_areNonOverlappingAndSumToUsedSpace() {
        val result = AppStorageStats.calculateCategoryBreakdown(
            AppStorageStats.RawCategoryStats(
                appBytes = 100,
                dataBytesIncludingCache = 500,
                cacheBytes = 100,
                externalTotalBytes = 1_000,
                externalAppBytes = 200,
                imageBytes = 300,
                videoBytes = 200,
                audioBytes = 100,
                totalCapacity = 5_000,
                totalFree = 1_000
            )
        )

        assertEquals(400, result.appDataSize)
        assertEquals(100, result.appCacheSize)
        assertEquals(200, result.otherSize)
        assertEquals(2_600, result.systemSize)
        assertEquals(
            result.totalUsed,
            result.appSize + result.appDataSize + result.appCacheSize +
                result.photosSize + result.videosSize + result.audioSize +
                result.otherSize + result.systemSize
        )
    }

    @Test
    fun malformedStats_neverProduceNegativeCategories() {
        val result = AppStorageStats.calculateCategoryBreakdown(
            AppStorageStats.RawCategoryStats(
                appBytes = -1,
                dataBytesIncludingCache = 50,
                cacheBytes = 100,
                externalTotalBytes = 10,
                externalAppBytes = 20,
                imageBytes = 30,
                videoBytes = 40,
                audioBytes = 50,
                totalCapacity = 100,
                totalFree = 200
            )
        )

        assertEquals(0, result.appSize)
        assertEquals(0, result.appDataSize)
        assertEquals(0, result.otherSize)
        assertEquals(0, result.totalUsed)
    }
}
