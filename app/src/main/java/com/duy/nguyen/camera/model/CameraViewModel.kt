package com.duy.nguyen.camera.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duy.nguyen.camera.controller.Camera2Controller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class CameraUiState(
    val aspect: CameraViewModel.Aspect = CameraViewModel.Aspect.RATIO_3_4,
    val flashEnabled: Boolean = false,
    val isCapturing: Boolean = false,
    val isPreviewReady: Boolean = false,
)

class CameraViewModel(
    private val controller: Camera2Controller
) : ViewModel() {
    private val _ui = MutableStateFlow(CameraUiState())
    val ui: StateFlow<CameraUiState> = _ui

    fun setAspect(aspect: Aspect) {
        if (_ui.value.aspect == aspect) return
        _ui.value = _ui.value.copy(aspect = aspect)
        viewModelScope.launch { controller.onAspectChanged(aspect) }
    }

    fun toggleFlash() {
        val next = !_ui.value.flashEnabled
        _ui.value = _ui.value.copy(flashEnabled = next)
        viewModelScope.launch { controller.setFlash(next) }
    }

    fun startPreviewIfNeeded() = viewModelScope.launch {
        controller.startPreview(onReady = { ready ->
            _ui.value = _ui.value.copy(isPreviewReady = ready)
        })
    }

    fun pauseCamera() = viewModelScope.launch { controller.pause() }
    fun resumeCamera() = viewModelScope.launch { controller.resume() }

    fun stopCamera() = viewModelScope.launch { controller.stop() }

    fun switchCamera() = viewModelScope.launch { controller.switchCamera() }
    fun capture() = viewModelScope.launch {
        _ui.value = _ui.value.copy(isCapturing = true)
        try {
            controller.captureStill()
        } finally {
            _ui.value = _ui.value.copy(isCapturing = false)
        }
    }

    enum class Aspect(val w: Int, val h: Int, val label: String) {
        RATIO_16_9(16, 9, "16:9"),
        RATIO_3_4(3, 4, "3:4"),
        RATIO_1_1(1, 1, "1:1");
    }
}
