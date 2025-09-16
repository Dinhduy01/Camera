package com.duy.nguyen.camera.model

import android.content.Context
import android.view.TextureView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duy.nguyen.camera.controller.Camera2Controller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import android.hardware.camera2.CameraManager

data class CameraUiState(
    val aspect: CameraViewModel.Aspect = CameraViewModel.Aspect.RATIO_3_4,
    val flashEnabled: Boolean = false,
    val isCapturing: Boolean = false,
    val isPreviewReady: Boolean = false,
    val isFront: Boolean = false,
    val mode: Mode = Mode.PHOTO,
    val isRecording: Boolean = false,
    val isChangingAspect: Boolean = false
) {
    enum class Mode { PHOTO, VIDEO }
}

class CameraViewModel(
    private val controller: Camera2Controller
) : ViewModel() {

    companion object {
        fun from(context: Context, cm: CameraManager): CameraViewModel {
            return CameraViewModel(Camera2Controller(context, cm))
        }
    }

    private val _ui = MutableStateFlow(CameraUiState())
    val ui: StateFlow<CameraUiState> = _ui

    fun attachPreview(view: TextureView) = controller.attachPreview(view)
    fun getPreviewBufferSize() = controller.getPreviewBufferSize()

    private fun refreshFacing() {
        _ui.value = _ui.value.copy(isFront = controller.isFrontCamera())
    }

    fun setAspect(aspect: Aspect) {
        val cur = _ui.value
        if (cur.aspect == aspect || cur.isChangingAspect) return
        _ui.value = cur.copy(aspect = aspect, isChangingAspect = true)
        viewModelScope.launch {
            controller.restartPreviewWithAspect(aspect) { ready ->
                _ui.value = _ui.value.copy(isPreviewReady = ready, isChangingAspect = false)
            }
        }
    }
    fun toggleFlash() {
        val next = !_ui.value.flashEnabled
        _ui.value = _ui.value.copy(flashEnabled = next)
        viewModelScope.launch { controller.setFlash(next) }
    }

    fun setMode(mode: CameraUiState.Mode) {
        val cur = _ui.value
        if (cur.mode == mode || cur.isChangingAspect) return
        _ui.value = cur.copy(mode = mode)
        pauseCamera()
        controller.isRecord = mode == CameraUiState.Mode.VIDEO
        if (controller.isRecord && controller.currentAspectPhoto == Aspect.RATIO_3_4) {
            setAspect(controller.currentAspectVideo)
        } else {
            setAspect(controller.currentAspectPhoto)
        }
        resumeCamera()
    }

    fun startPreviewIfNeeded() = viewModelScope.launch {
        controller.startPreview(onReady = { ready ->
            _ui.value = _ui.value.copy(isPreviewReady = ready)
        })
        refreshFacing()
    }

    fun pauseCamera() = viewModelScope.launch { controller.pause() }
    fun resumeCamera() = viewModelScope.launch {
        controller.resume()
        refreshFacing()
    }
    fun stopCamera() = viewModelScope.launch { controller.stop() }
    fun switchCamera() = viewModelScope.launch {
        controller.switchCamera()
        refreshFacing()
    }

    fun capture() = viewModelScope.launch {
        if (_ui.value.mode != CameraUiState.Mode.PHOTO) return@launch
        _ui.value = _ui.value.copy(isCapturing = true)
        try { controller.captureStill() } finally {
            _ui.value = _ui.value.copy(isCapturing = false)
        }
    }

    fun toggleRecord() = viewModelScope.launch {
        if (_ui.value.mode != CameraUiState.Mode.VIDEO) return@launch
        if (_ui.value.isRecording) {
            controller.stopRecording()
            _ui.value = _ui.value.copy(isRecording = false)
        } else {
            controller.startRecording()
            _ui.value = _ui.value.copy(isRecording = true)
        }
    }

    enum class Aspect(val w: Int, val h: Int, val label: String) {
        RATIO_16_9(16, 9, "16:9"),
        RATIO_3_4(3, 4, "3:4"),
        RATIO_1_1(1, 1, "1:1");
    }
}
