package com.kvita.diskmapper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPrivateAccountingTest {
    @Test
    fun rootTotals_ignoreNestedAggregateRows() {
        val totals = AndroidPrivateAccounting.rootTotals(
            listOf(
                PathBytes("/storage/emulated/0/Android/data", 100, 120),
                PathBytes("/storage/emulated/0/Android/data/com.example", 80, 96),
                PathBytes("/storage/emulated/0/Android/obb", 50, 64),
                PathBytes("/storage/emulated/0/Android/obb/com.example/main.obb", 40, 48)
            )
        )

        assertEquals(ByteTotals(logicalBytes = 150, onDiskBytes = 184), totals)
    }

    @Test
    fun replacingPrivateTotals_doesNotDoubleCountChildren() {
        val result = AndroidPrivateAccounting.replaceRootTotals(
            base = ByteTotals(logicalBytes = 1_000, onDiskBytes = 1_100),
            basePrivate = ByteTotals(logicalBytes = 100, onDiskBytes = 120),
            replacementPrivate = ByteTotals(logicalBytes = 300, onDiskBytes = 360)
        )

        assertEquals(ByteTotals(logicalBytes = 1_200, onDiskBytes = 1_340), result)
    }

    @Test
    fun privatePathMatching_isBoundarySafe() {
        assertTrue(AndroidPrivateAccounting.isPrivatePath("/storage/emulated/0/Android/data/com.example"))
        assertTrue(AndroidPrivateAccounting.isPrivatePath("/storage/emulated/10/Android/data/com.example"))
        assertTrue(AndroidPrivateAccounting.isPrivatePath("/data/media/10/Android/obb/com.game/main.obb"))
        assertTrue(AndroidPrivateAccounting.isPrivatePath("/storage/emulated/0/Android/obb"))
        assertTrue(AndroidPrivateAccounting.isPrivateRoot("/storage/emulated/10/Android/data/"))
        assertFalse(AndroidPrivateAccounting.isPrivatePath("/storage/emulated/0/Android/database"))
        assertFalse(AndroidPrivateAccounting.isPrivateRoot("/storage/emulated/0/Android/data/com.example"))
        assertFalse(AndroidPrivateAccounting.isPrivatePath(null))
    }
}
