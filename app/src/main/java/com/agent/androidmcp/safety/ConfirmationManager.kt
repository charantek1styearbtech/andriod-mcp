package com.agent.androidmcp.safety

import android.util.Log
import com.agent.androidmcp.action.AgentAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ConfirmationManager {
    private const val TAG = "ConfirmationManager"

    private val _activeRequest = MutableStateFlow<ConfirmationRequest?>(null)
    val activeRequest: StateFlow<ConfirmationRequest?> = _activeRequest.asStateFlow()

    suspend fun requestConfirmation(action: AgentAction, evaluation: SafetyEvaluation): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val request = ConfirmationRequest(
            action = action,
            evaluation = evaluation,
            deferredResult = deferred
        )

        _activeRequest.value = request
        Log.i(TAG, "Confirmation requested: ${evaluation.riskCategory} - ${evaluation.preview}")

        return try {
            val result = deferred.await()
            Log.i(TAG, "Confirmation resolved: allowed=$result for action $action")
            result
        } finally {
            if (_activeRequest.value?.id == request.id) {
                _activeRequest.value = null
            }
        }
    }

    fun resolveConfirmation(id: String, allowed: Boolean) {
        val current = _activeRequest.value
        if (current != null && current.id == id) {
            current.deferredResult.complete(allowed)
            _activeRequest.value = null
        }
    }

    fun cancelAll() {
        val current = _activeRequest.value
        if (current != null) {
            current.deferredResult.complete(false)
            _activeRequest.value = null
        }
    }
}
