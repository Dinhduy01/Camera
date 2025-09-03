package com.duy.nguyen.camera

import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.duy.nguyen.camera.model.CameraViewModel.Aspect
import com.duy.nguyen.camera.controller.Camera2Controller
import com.duy.nguyen.camera.model.CameraUiState
import com.duy.nguyen.camera.model.CameraViewModel
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val vm: CameraViewModel by viewModels {
        val cm = getSystemService(CameraManager::class.java)
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val controller = Camera2Controller(this@MainActivity, cm)
                return CameraViewModel(controller) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CameraScreen(vm = vm)
            LaunchedEffect(Unit) { vm.startPreviewIfNeeded() }
        }
    }

    override fun onPause() {
        super.onPause()
        vm.pauseCamera()
    }

    override fun onResume() {
        super.onResume()
        vm.resumeCamera()
    }
}

@Composable
fun CameraScreen(vm: CameraViewModel) {
    val ui by vm.ui.collectAsState()

    val aspectAnim by animateFloatAsState(
        targetValue = when (ui.aspect) {
            Aspect.RATIO_16_9 -> 9f / 16f
            Aspect.RATIO_3_4 -> 3f / 4f
            Aspect.RATIO_1_1 -> 1f
        },
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "aspectAnim"
    )

    val topOffsetTarget = when (ui.aspect) {
        Aspect.RATIO_16_9 -> 35.dp
        Aspect.RATIO_3_4 -> 150.dp
        Aspect.RATIO_1_1 -> 0.dp
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspectAnim)
                .align(if (ui.aspect == Aspect.RATIO_1_1) Alignment.Center else Alignment.TopCenter)
                .offset(y = topOffsetTarget)
                .then(if (!ui.isRecording) Modifier.pointerInput(Unit) {
                    var accV = 0f
                    val threshold = 120f
                    val cooldownMs = 350L
                    var lastFlip = 0L
                    detectVerticalDragGestures(
                        onDragStart = { accV = 0f },
                        onVerticalDrag = { change, dy -> accV += dy; change.consume() },
                        onDragEnd = {
                            val now = System.currentTimeMillis()
                            if (abs(accV) > threshold && now - lastFlip > cooldownMs) {
                                lastFlip = now; vm.switchCamera()
                            }
                        })
                } else Modifier)
                .then(if (!ui.isRecording) Modifier.pointerInput(ui.mode) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            if (dx > 0) vm.setMode(CameraUiState.Mode.PHOTO)
                            else if (dx < 0) vm.setMode(CameraUiState.Mode.VIDEO)
                        })
                } else Modifier)) { CameraPreviewLayer(vm) }

        if (!ui.isRecording) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AspectSelector(current = ui.aspect, onSelect = vm::setAspect)
                Spacer(Modifier.height(10.dp))

                LazyRow(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    items(listOf(CameraUiState.Mode.PHOTO, CameraUiState.Mode.VIDEO)) { m ->
                        val selected = (m == ui.mode)
                        AssistChip(
                            onClick = { vm.setMode(m) },
                            label = { Text(if (m == CameraUiState.Mode.PHOTO) "Photo" else "Video") },
                            modifier = Modifier.padding(horizontal = 6.dp),
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(
                                    alpha = 0.15f
                                )
                                else MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                CaptureRow(
                    ui = ui,
                    onToggleFlash = vm::toggleFlash,
                    onCapture = vm::capture,
                    onToggleRecord = vm::toggleRecord,
                    onFlip = vm::switchCamera
                )
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp), contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(Color.Red)
                        .clickable { vm.toggleRecord() })
            }
        }

    }
}

@Composable
private fun CameraPreviewLayer(vm: CameraViewModel) {
    val ctx = LocalContext.current
    val controllerField =
        CameraViewModel::class.java.getDeclaredField("controller").apply { isAccessible = true }
    val controller = controllerField.get(vm) as Camera2Controller

    AndroidView(
        factory = { TextureView(ctx).also { controller.attachPreview(it) } },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun AspectSelector(current: Aspect, onSelect: (Aspect) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .clip(CircleShape)
            .background(Color(0x33000000))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(Aspect.RATIO_16_9, Aspect.RATIO_3_4, Aspect.RATIO_1_1).forEach { asp ->
            val selected = asp == current
            val scale by animateFloatAsState(if (selected) 1.0f else 0.94f, tween(180), label = "")
            Text(
                asp.label,
                color = if (selected) Color.White else Color(0xFFBDBDBD),
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clickable { onSelect(asp) })
        }
    }
}

@Composable
private fun CaptureRow(
    ui: CameraUiState,
    onToggleFlash: () -> Unit,
    onCapture: () -> Unit,
    onToggleRecord: () -> Unit,
    onFlip: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        AssistButton(
            text = if (ui.flashEnabled) "Flash On" else "Flash Off", onClick = onToggleFlash
        )
        Spacer(Modifier.width(28.dp))
        ModeAwareCaptureButton(
            mode = ui.mode,
            isRecording = ui.isRecording,
            onCapture = onCapture,
            onToggleRecord = onToggleRecord
        )
        Spacer(Modifier.width(28.dp))
        AssistButton(text = "Flip", onClick = onFlip)
    }
}

@Composable
private fun AssistButton(text: String, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = Color(0x22FFFFFF)) {
        Text(
            text = text,
            color = Color.White,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .clickable { onClick() })
    }
}

@Composable
private fun ModeAwareCaptureButton(
    mode: CameraUiState.Mode,
    isRecording: Boolean,
    onCapture: () -> Unit,
    onToggleRecord: () -> Unit
) {
    val size = 76.dp
    val stroke = 5.dp

    when (mode) {
        CameraUiState.Mode.PHOTO -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(stroke, Color(0x33000000), CircleShape)
                    .clickable { onCapture() })
        }

        CameraUiState.Mode.VIDEO -> {
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .size(size)
                        .clip(CircleShape)
                        .background(Color.Red)
                        .clickable { onToggleRecord() })
            } else {
                Box(
                    modifier = Modifier
                        .size(size)
                        .clip(CircleShape)
                        .background(Color.Red)
                        .border(stroke, Color.White, CircleShape)
                        .clickable { onToggleRecord() })
            }
        }
    }
}
