package com.RooMCutNext.export

import android.net.Uri

/**
 * Singleton communication bus between the export foreground service and the UI.
 * The UI collects [state]; the service is the only writer.
 */
object ExportBus {

    enum class Phase { RUNNING, COMPLETED, FAILED, CANCELLED }

    data class ExportState(
        val projectId: String,
        val projectName: String,
        val phase: Phase,
        /** 0..1, or -1 while indeterminate. */
        val progress: Float,
        /** Estimated seconds remaining, null when unknown. */
        val etaSeconds: Long? = null,
        val outputUri: Uri? = null,
        val outputPathHint: String? = null,
        val error: String? = null,
    )

    private val _state = kotlinx.coroutines.flow.MutableStateFlow<ExportState?>(null)
    val state: kotlinx.coroutines.flow.StateFlow<ExportState?> = _state

    fun update(state: ExportState) {
        _state.value = state
    }

    fun clear() {
        _state.value = null
    }
}
