package com.kvita.diskmapper.data

import android.net.Uri

data class StorageItem(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val mimeType: String?
)

data class ScanResult(
    val items: List<StorageItem>,
    val visitedNodes: Long,
    val rootSizeBytes: Long
)

