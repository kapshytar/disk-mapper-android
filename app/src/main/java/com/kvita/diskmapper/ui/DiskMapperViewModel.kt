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
    ALL_FILES
}

data class DiskMapperUiState(
    val selectedFolderUri: Uri? = null,
    val selectedRootPath: String? = null,
    val scanSource: ScanSource = ScanSource.SAF,
    val isScanning: Boolean = false,
    val visitedNodes: Long = 0,
    val rootLogicalSizeBytes: Long = 0,
    val rootOnDiskSizeBytes: Long = 0,
    val items: List<StorageItem> = emptyList(),
    val errorMessage: String? = null
)

class DiskMapperViewModel : ViewModel() {
    private val scanner = StorageScanner()

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

    fun deleteItem(context: Context, item: StorageItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = if (_uiState.value.scanSource == ScanSource.ALL_FILES && item.absolutePath != null) {
                scanner.deleteFile(item.absolutePath)
            } else {
                scanner.delete(context.applicationContext, item.uri)
            }
            if (ok) {
                scan(context)
            } else {
                _uiState.update { it.copy(errorMessage = "Failed to delete ${item.name}") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}

