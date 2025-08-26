package com.duy.nguyen.camera


import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.duy.nguyen.camera.controller.Camera2Controller
import com.duy.nguyen.camera.model.CameraViewModel
import com.duy.nguyen.camera.model.CameraViewModel.Aspect
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val vm: CameraViewModel by viewModels {
        val cm = getSystemService(CameraManager::class.java)
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val controller =
                    com.duy.nguyen.camera.controller.Camera2Controller(this@MainActivity, cm)
                return CameraViewModel(controller) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CameraScreen(vm = vm, onBack = { finish() })
            LifecycleHook(vm)
        }
    }
}

@Composable
private fun LifecycleHook(vm: CameraViewModel) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.resumeCamera()
                Lifecycle.Event.ON_PAUSE -> vm.pauseCamera()
                Lifecycle.Event.ON_STOP -> vm.stopCamera()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { vm.startPreviewIfNeeded() }
}

@Composable
fun CameraScreen(vm: CameraViewModel, onBack: () -> Unit) {
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
        Aspect.RATIO_3_4  -> 150.dp
        Aspect.RATIO_1_1  -> 0.dp
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
                .align(
                    if (ui.aspect == Aspect.RATIO_1_1) Alignment.Center
                    else Alignment.TopCenter
                )
                .offset(y = topOffsetTarget)
                .pointerInput(Unit) {
                    var acc = 0f
                    val threshold = 120f
                    val cooldownMs = 350L
                    var lastFlip = 0L

                    detectVerticalDragGestures(
                        onDragStart = { acc = 0f },
                        onVerticalDrag = { change, dy ->
                            acc += dy
                            change.consume()
                        },
                        onDragEnd = {
                            val now = System.currentTimeMillis()
                            if (abs(acc) > threshold && now - lastFlip > cooldownMs) {
                                lastFlip = now
                                vm.switchCamera()
                            }
                        }
                    )
                }
        ) {
            CameraPreviewLayer(vm)
        }

        // Bottom controls
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AspectSelector(current = ui.aspect, onSelect = vm::setAspect)
            Spacer(Modifier.height(12.dp))
            CaptureRow(
                flashEnabled = ui.flashEnabled,
                onToggleFlash = vm::toggleFlash,
                onCapture = vm::capture,
                onFlip = vm::switchCamera
            )
        }

        // shutter flash overlay (trắng, fade-out nhanh)
        var flashTrigger by remember { mutableStateOf(0) }
        LaunchedEffect(ui.isCapturing) {
            if (ui.isCapturing) flashTrigger++
        }
        if (ui.isCapturing) {
            var alpha by remember(flashTrigger) { mutableStateOf(0.35f) }
            LaunchedEffect(flashTrigger) {
                animate(
                    initialValue = 0.35f,
                    targetValue = 0f,
                    animationSpec = tween(120, easing = LinearEasing)
                ) { value, _ -> alpha = value }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = alpha))
            )
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
        factory = { _ ->
            TextureView(ctx).also { tv ->
                controller.attachPreview(tv)
            }
        }, modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun AspectSelector(
    current: Aspect, onSelect: (Aspect) -> Unit
) {
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .clip(CircleShape)
            .background(Color(0x33000000))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(
            Aspect.RATIO_16_9, Aspect.RATIO_3_4, Aspect.RATIO_1_1
        ).forEach { asp ->
            val selected = asp == current
            val scale by animateFloatAsState(if (selected) 1.0f else 0.94f, tween(180), label = "")
            Text(
                asp.label,
                color = if (selected) Color.White else Color(0xFFBDBDBD),
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .graphicsLayer { this.scaleX = scale; this.scaleY = scale }
                    .clickable { onSelect(asp) })
        }
    }
}

@Composable
private fun CaptureRow(
    flashEnabled: Boolean,
    onToggleFlash: () -> Unit,
    onCapture: () -> Unit,
    onFlip: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        AssistButton(text = if (flashEnabled) "Flash On" else "Flash Off", onClick = onToggleFlash)
        Spacer(Modifier.width(28.dp))
        CaptureButton(onCapture)
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
private fun CaptureButton(onCapture: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f, animationSpec = spring(), label = "btnScale"
    )

    Box(
        Modifier
            .size(78.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(Color.White)
            .clickable {
                pressed = true
                onCapture()
            }, contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.06f))
        )
    }

    LaunchedEffect(pressed) {
        if (pressed) {
            kotlinx.coroutines.delay(120)
            pressed = false
        }
    }
}
