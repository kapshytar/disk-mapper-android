package com.kvita.diskmapper.data

internal data class PathBytes(
    val path: String?,
    val logicalBytes: Long,
    val onDiskBytes: Long
)

internal data class ByteTotals(
    val logicalBytes: Long,
    val onDiskBytes: Long
)

internal object AndroidPrivateAccounting {
    private val emulatedPrivatePath =
        Regex("^/storage/emulated/\\d+/Android/(data|obb)(?:/.*)?$")
    private val dataMediaPrivatePath =
        Regex("^/data/media/\\d+/Android/(data|obb)(?:/.*)?$")
    private val aliasPrivatePath =
        Regex("^/(sdcard|storage/self/primary)/Android/(data|obb)(?:/.*)?$")

    fun isPrivateRoot(path: String?): Boolean {
        if (!isPrivatePath(path)) return false
        val normalized = path?.trimEnd('/') ?: return false
        return normalized.endsWith("/Android/data") || normalized.endsWith("/Android/obb")
    }

    fun isPrivatePath(path: String?): Boolean {
        if (path == null) return false
        val normalized = path.trimEnd('/')
        return emulatedPrivatePath.matches(normalized) ||
            dataMediaPrivatePath.matches(normalized) ||
            aliasPrivatePath.matches(normalized)
    }

    fun rootTotals(items: Iterable<PathBytes>): ByteTotals {
        var logical = 0L
        var onDisk = 0L
        for (item in items) {
            if (isPrivateRoot(item.path)) {
                logical += item.logicalBytes
                onDisk += item.onDiskBytes
            }
        }
        return ByteTotals(logical, onDisk)
    }

    fun replaceRootTotals(
        base: ByteTotals,
        basePrivate: ByteTotals,
        replacementPrivate: ByteTotals
    ): ByteTotals = ByteTotals(
        logicalBytes = (base.logicalBytes - basePrivate.logicalBytes + replacementPrivate.logicalBytes)
            .coerceAtLeast(0L),
        onDiskBytes = (base.onDiskBytes - basePrivate.onDiskBytes + replacementPrivate.onDiskBytes)
            .coerceAtLeast(0L)
    )
}
