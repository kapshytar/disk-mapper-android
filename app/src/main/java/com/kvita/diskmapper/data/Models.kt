package com.kvita.diskmapper.data

import android.net.Uri

data class StorageItem(
    val uri: Uri,
    val absolutePath: String? = null,
    val name: String,
    val logicalSizeBytes: Long,
    val onDiskSizeBytes: Long,
    val isDirectory: Boolean,
    val mimeType: String?
)

data class ScanResult(
    val items: List<StorageItem>,
    val visitedNodes: Long,
    val rootLogicalSizeBytes: Long,
    val rootOnDiskSizeBytes: Long
)

