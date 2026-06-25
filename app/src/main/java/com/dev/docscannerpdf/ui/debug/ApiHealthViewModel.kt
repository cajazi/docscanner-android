package com.dev.docscannerpdf.ui.debug

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dev.docscannerpdf.network.ApiConfig
import com.dev.docscannerpdf.network.EngineCapabilitiesResponse
import com.dev.docscannerpdf.network.HealthResponse
import com.dev.docscannerpdf.network.NetworkResult
import com.dev.docscannerpdf.process.ProcessDocumentUiState
import com.dev.docscannerpdf.process.ProcessDocumentUseCase
import com.dev.docscannerpdf.repository.DocScannerRemoteRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ApiHealthUiState(
    val isLoading: Boolean = false,
    val baseUrl: String = ApiConfig.defaultBaseUrl,
    val responseTimeMs: Long? = null,
    val health: HealthResponse? = null,
    val capabilities: EngineCapabilitiesResponse? = null,
    val processState: ProcessDocumentUiState = ProcessDocumentUiState.Idle,
    val errorMessage: String? = null
) {
    val isConnected: Boolean
        get() = !isLoading && errorMessage == null && health != null && capabilities != null

    val connectionStatus: String
        get() = when {
            isLoading -> "Checking"
            isConnected -> "Connected"
            errorMessage != null -> "Disconnected"
            else -> "Not checked"
        }
}

class ApiHealthViewModel(
    private val repository: DocScannerRemoteRepository = DocScannerRemoteRepository(),
    private val processDocumentUseCase: ProcessDocumentUseCase = ProcessDocumentUseCase(repository)
) : ViewModel() {
    private val _uiState = MutableStateFlow(ApiHealthUiState(isLoading = true))
    val uiState: StateFlow<ApiHealthUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    responseTimeMs = null,
                    errorMessage = null
                )
            }

            val startedAt = SystemClock.elapsedRealtime()
            val healthResult = repository.getHealth()
            val capabilitiesResult = repository.getEngineCapabilities()
            val elapsed = SystemClock.elapsedRealtime() - startedAt

            val health = (healthResult as? NetworkResult.Success)?.data
            val capabilities = (capabilitiesResult as? NetworkResult.Success)?.data
            val error = firstErrorMessage(healthResult, capabilitiesResult)

            _uiState.update {
                it.copy(
                    isLoading = false,
                    responseTimeMs = elapsed,
                    health = health,
                    capabilities = capabilities,
                    errorMessage = error
                )
            }
        }
    }

    fun processImage(
        context: Context,
        imageUri: Uri
    ) {
        viewModelScope.launch {
            processDocumentUseCase.processCapturedImage(
                context = context.applicationContext,
                imageUri = imageUri,
                onState = { processState ->
                    _uiState.update { it.copy(processState = processState) }
                }
            )
        }
    }

    private fun firstErrorMessage(
        healthResult: NetworkResult<HealthResponse>,
        capabilitiesResult: NetworkResult<EngineCapabilitiesResponse>
    ): String? {
        return resultErrorMessage("Health", healthResult)
            ?: resultErrorMessage("Capabilities", capabilitiesResult)
    }

    private fun resultErrorMessage(
        label: String,
        result: NetworkResult<*>
    ): String? {
        return when (result) {
            is NetworkResult.Success -> null
            is NetworkResult.Error -> {
                val detail = result.errorBody?.takeIf { it.isNotBlank() } ?: result.message
                "$label failed: HTTP ${result.code} $detail"
            }
            is NetworkResult.Exception -> "$label failed: ${result.throwable.message ?: result.throwable::class.java.simpleName}"
        }
    }
}
