package com.radiozport.ninegfiles.utils

import com.radiozport.ninegfiles.data.model.FileItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-singleton clipboard for file operations.
 * Lives longer than any ViewModel — attached to the Application.
 * Survives navigation between fragments and Activity recreation.
 */
object FileClipboardManager {

    enum class Operation { COPY, CUT }

    data class ClipboardState(
        val files: List<FileItem> = emptyList(),
        val operation: Operation = Operation.COPY
    ) {
        val isEmpty: Boolean get() = files.isEmpty()
        val isCut: Boolean get() = operation == Operation.CUT
        val count: Int get() = files.size
        val summary: String get() = when {
            isEmpty -> ""
            operation == Operation.COPY -> "Copy ${count} item(s)"
            else -> "Move ${count} item(s)"
        }
    }

    private val _state = MutableStateFlow(ClipboardState())
    val state: StateFlow<ClipboardState> = _state.asStateFlow()

    fun copy(files: List<FileItem>) {
        _state.value = ClipboardState(files = files, operation = Operation.COPY)
    }

    fun cut(files: List<FileItem>) {
        _state.value = ClipboardState(files = files, operation = Operation.CUT)
    }

    fun clear() {
        _state.value = ClipboardState()
    }

    val hasContent: Boolean get() = !_state.value.isEmpty
    val isCut: Boolean get() = _state.value.isCut
    val files: List<FileItem> get() = _state.value.files
}
