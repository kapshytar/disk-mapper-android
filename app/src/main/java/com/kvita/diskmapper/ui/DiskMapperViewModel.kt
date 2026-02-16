package com.kvita.diskmapper.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kvita.diskmapper.data.StorageItem
import com.kvita.diskmapper.data.StorageScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ScanSource {
    SAF,
    ALL_FILES,
    SHIZUKU_ANDROID
}

data class DiskMapperUiState(
    val selectedFolderUri: Uri? = null,
    val selectedRootPath: String? = null,
    val scanSource: ScanSource = ScanSource.SAF,
    val isScanning: Boolean = false,
    val visitedNodes: Long = 0,
    val rootLogicalSizeBytes: Long = 0,
    val rootOnDiskSizeBytes: Long = 0,
    val shizukuTelegramOnly: Boolean = false,
    val items: List<StorageItem> = emptyList(),
    val errorMessage: String? = null
)

class DiskMapperViewModel : ViewModel() {
    private val scanner = StorageScanner()
    private val shizukuBridge = ShizukuBridge()

    private val _uiState = MutableStateFlow(DiskMapperUiState())
    val uiState: StateFlow<DiskMapperUiState> = _uiState.asStateFlow()

    fun restorePersistedFolder(context: Context) {
        if (_uiState.value.selectedFolderUri != null || _uiState.value.selectedRootPath != null) return
        val persisted = context.contentResolver.persistedUriPermissions.firstOrNull()?.uri ?: return
        _uiState.update { it.copy(selectedFolderUri = persisted, scanSource = ScanSource.SAF) }
        scan(context)
    }

    fun selectFolder(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
        }

        _uiState.update {
            it.copy(
                selectedFolderUri = uri,
                selectedRootPath = null,
                scanSource = ScanSource.SAF,
                errorMessage = null
            )
        }
        scan(context)
    }

    fun selectAllFilesRoot(path: String, context: Context) {
        _uiState.update {
            it.copy(
                selectedFolderUri = null,
                selectedRootPath = path,
                scanSource = ScanSource.ALL_FILES,
                errorMessage = null
            )
        }
        scan(context)
    }

    fun scanAndroidPrivateWithShizuku(context: Context, telegramOnly: Boolean) {
        when (shizukuBridge.ensurePermission()) {
            ShizukuBridge.PermissionState.SHIZUKU_NOT_RUNNING -> {
                _uiState.update {
                    it.copy(errorMessage = "Shizuku is not running. Start Shizuku first.")
                }
            }
            ShizukuBridge.PermissionState.PERMISSION_REQUESTED -> {
                _uiState.update {
                    it.copy(errorMessage = "Shizuku permission requested. Confirm and tap again.")
                }
            }
            ShizukuBridge.PermissionState.PERMISSION_DENIED -> {
                _uiState.update {
                    it.copy(errorMessage = "Shizuku permission denied.")
                }
            }
            ShizukuBridge.PermissionState.READY -> {
                _uiState.update {
                    it.copy(
                        selectedFolderUri = null,
                        selectedRootPath = "/storage/emulated/0/Android",
                        scanSource = ScanSource.SHIZUKU_ANDROID,
                        shizukuTelegramOnly = telegramOnly,
                        errorMessage = null
                    )
                }
                scanShizuku(context, telegramOnly)
            }
        }
    }

    fun scan(context: Context) {
        val state = _uiState.value

        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, visitedNodes = 0, errorMessage = null) }

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (state.scanSource) {
                        ScanSource.SAF -> {
                            val rootUri = state.selectedFolderUri
                                ?: return@runCatching throw IllegalStateException("Folder is not selected")
                            scanner.scan(context.applicationContext, rootUri) { visited ->
                                _uiState.update { it.copy(visitedNodes = visited) }
                            }
                        }
                        ScanSource.ALL_FILES -> {
                            val rootPath = state.selectedRootPath
                                ?: return@runCatching throw IllegalStateException("Root path is not selected")
                            scanner.scanFileTree(File(rootPath)) { visited ->
                                _uiState.update { it.copy(visitedNodes = visited) }
                            }
                        }
                        ScanSource.SHIZUKU_ANDROID -> {
                            return@runCatching throw IllegalStateException(
                                "Use Shizuku scan action for Android/data and Android/obb."
                            )
                        }
                    }
                }
            }

            result.onSuccess { scanResult ->
                _uiState.update {
                    it.copy(
                    isScanning = false,
                    visitedNodes = scanResult.visitedNodes,
                    rootLogicalSizeBytes = scanResult.rootLogicalSizeBytes,
                    rootOnDiskSizeBytes = scanResult.rootOnDiskSizeBytes,
                    items = scanResult.items
                )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                    isScanning = false,
                    errorMessage = throwable.message ?: "Scan failed"
                )
                }
            }
        }
    }

    private fun scanShizuku(context: Context, telegramOnly: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, visitedNodes = 0, errorMessage = null) }

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val payload = shizukuBridge.scanAndroidPrivate(context.applicationContext, telegramOnly)
                    parseShizukuPayload(payload)
                }
            }

            result.onSuccess { items ->
                val logical = items.sumOf { it.logicalSizeBytes }
                val onDisk = items.sumOf { it.onDiskSizeBytes }
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        visitedNodes = items.size.toLong(),
                        rootLogicalSizeBytes = logical,
                        rootOnDiskSizeBytes = onDisk,
                        items = items.sortedByDescending { item -> item.onDiskSizeBytes }
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        errorMessage = throwable.message ?: "Shizuku scan failed"
                    )
                }
            }
        }
    }

    fun deleteItem(context: Context, item: StorageItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = if (_uiState.value.scanSource == ScanSource.ALL_FILES && item.absolutePath != null) {
                scanner.deleteFile(item.absolutePath)
            } else if (_uiState.value.scanSource == ScanSource.SHIZUKU_ANDROID && item.absolutePath != null) {
                runCatching {
                    shizukuBridge.deleteFile(context.applicationContext, item.absolutePath)
                }.getOrDefault(false)
            } else {
                scanner.delete(context.applicationContext, item.uri)
            }
            if (ok) {
                if (_uiState.value.scanSource == ScanSource.SHIZUKU_ANDROID) {
                    scanShizuku(context, _uiState.value.shizukuTelegramOnly)
                } else {
                    scan(context)
                }
            } else {
                _uiState.update { it.copy(errorMessage = "Failed to delete ${item.name}") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun parseShizukuPayload(payload: String): List<StorageItem> {
        if (payload.isBlank()) return emptyList()
        val separator = '\u001F'
        return payload
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split(separator)
                if (parts.size < 5) return@mapNotNull null
                val path = parts[0]
                val name = parts[1]
                val logical = parts[2].toLongOrNull() ?: 0L
                val onDisk = parts[3].toLongOrNull() ?: logical
                val isDirectory = parts[4] == "1"
                StorageItem(
                    uri = Uri.fromFile(File(path)),
                    absolutePath = path,
                    name = name,
                    logicalSizeBytes = logical,
                    onDiskSizeBytes = onDisk,
                    isDirectory = isDirectory,
                    mimeType = null
                )
            }
            .toList()
    }
}

