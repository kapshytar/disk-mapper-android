package com.kvita.diskmapper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertEquals(4_000, result.totalUsed)
        assertEquals(1_000, result.totalFree)
        assertEquals(2_600, result.systemSize)
        assertEquals(
            result.totalUsed,
            result.appSize + result.appDataSize + result.appCacheSize +
                result.photosSize + result.videosSize + result.audioSize +
                result.otherSize + result.systemSize
        )
        assertEquals(result.totalCapacity, result.totalUsed + result.totalFree)
    }

    /** Categories must never sum past the disk, whatever the platform reports. */
    @Test
    fun measuredCategoriesExceedingDerivedUsed_stillSumToUsed() {
        val result = AppStorageStats.calculateCategoryBreakdown(
            AppStorageStats.RawCategoryStats(
                appBytes = 4_000,
                dataBytesIncludingCache = 4_000,
                cacheBytes = 0,
                externalTotalBytes = 0,
                externalAppBytes = 0,
                imageBytes = 0,
                videoBytes = 0,
                audioBytes = 0,
                totalCapacity = 10_000,
                // Claims 9_000 free while apps alone measure 8_000 used.
                totalFree = 9_000
            )
        )

        val sum = result.appSize + result.appDataSize + result.appCacheSize +
            result.photosSize + result.videosSize + result.audioSize +
            result.otherSize + result.systemSize
        assertEquals(8_000, result.totalUsed)
        assertEquals(0, result.systemSize)
        assertEquals(sum, result.totalUsed)
        assertEquals(result.totalCapacity, result.totalUsed + result.totalFree)
    }

    /** Cache larger than the reported free space must not invent used bytes. */
    @Test
    fun cacheLargerThanReportedFree_keepsFreeNonNegative() {
        val result = AppStorageStats.calculateCategoryBreakdown(
            AppStorageStats.RawCategoryStats(
                appBytes = 0,
                dataBytesIncludingCache = 3_000,
                cacheBytes = 3_000,
                externalTotalBytes = 0,
                externalAppBytes = 0,
                imageBytes = 0,
                videoBytes = 0,
                audioBytes = 0,
                totalCapacity = 10_000,
                totalFree = 1_000
            )
        )

        assertEquals(10_000, result.totalUsed + result.totalFree)
        assertTrue(result.totalFree >= 0)
        assertTrue(result.totalUsed <= result.totalCapacity)
    }

    /**
     * Regression, measured on a Galaxy S10+: feeding the advertised size from
     * `getTotalBytes()` (exactly 128 GiB) instead of the filesystem size showed
     * 107.5 GiB used / 20.5 GiB free where df said 83.8 / 25.2. Capacity and
     * free must come from statfs.
     */
    @Test
    fun filesystemFigures_matchDf() {
        val capacity = 28_571_259L * 4096   // statfs /data on the test device
        val free = 6_607_734L * 4096
        val result = AppStorageStats.calculateCategoryBreakdown(
            AppStorageStats.RawCategoryStats(
                appBytes = 33_000_000_000,
                dataBytesIncludingCache = 27_000_000_000,
                cacheBytes = 7_000_000_000,
                externalTotalBytes = 12_000_000_000,
                externalAppBytes = 4_000_000_000,
                imageBytes = 2_500_000_000,
                videoBytes = 2_900_000_000,
                audioBytes = 260_000_000,
                totalCapacity = capacity,
                totalFree = free
            )
        )

        assertEquals(capacity - free, result.totalUsed)
        assertEquals(free, result.totalFree)
        assertEquals(capacity, result.totalUsed + result.totalFree)
        val sum = result.appSize + result.appDataSize + result.appCacheSize +
            result.photosSize + result.videosSize + result.audioSize +
            result.otherSize + result.systemSize
        assertEquals(sum, result.totalUsed)
        assertTrue(result.systemSize > 0)
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
        assertEquals(0, result.systemSize)
        assertTrue(result.totalUsed >= 0)
        assertTrue(result.totalFree >= 0)
        // Contradictory input (parts larger than the disk) keeps the breakdown
        // self-consistent rather than pretending it fits.
        val sum = result.appSize + result.appDataSize + result.appCacheSize +
            result.photosSize + result.videosSize + result.audioSize +
            result.otherSize + result.systemSize
        assertEquals(sum, result.totalUsed)
    }
}
