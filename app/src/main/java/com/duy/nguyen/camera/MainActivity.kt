package com.duy.nguyen.camera

import android.graphics.Matrix
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.duy.nguyen.camera.model.CameraUiState
import com.duy.nguyen.camera.model.CameraViewModel
import com.duy.nguyen.camera.model.CameraViewModel.Aspect
import androidx.compose.ui.geometry.Size as CSize

class MainActivity : ComponentActivity() {

    // Factory chuẩn — override cả 2 create(...) để tránh crash lifecycle mới
    private val vm: CameraViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val cm = getSystemService(CameraManager::class.java)
                return CameraViewModel.from(this@MainActivity, cm) as T
            }

            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val cm = getSystemService(CameraManager::class.java)
                return CameraViewModel.from(this@MainActivity, cm) as T
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

    val frameAspect by animateFloatAsState(
        targetValue = when (ui.aspect) {
            Aspect.RATIO_16_9 -> 9f / 16f
            Aspect.RATIO_3_4 -> 3f / 4f
            Aspect.RATIO_1_1 -> 1f
        }, animationSpec = tween(260, easing = FastOutSlowInEasing), label = "frameAspect"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        CameraPreviewInFrame(
            vm = vm, frameAspect = frameAspect, modifier = Modifier.align(Alignment.Center)
        )

        AspectMaskAroundFrame(
            aspect = frameAspect, showGrid = true, modifier = Modifier.fillMaxSize()
        )

        // Controls
        if (!ui.isRecording) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AspectSelector(current = ui.aspect, onSelect = vm::setAspect, ui)
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
private fun CameraPreviewInFrame(
    vm: CameraViewModel,
    frameAspect: Float,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var tvRef by remember { mutableStateOf<TextureView?>(null) }
    val ui by vm.ui.collectAsState()

    // Gestures chỉ trong khung preview
    val gestureModifier =
        if (!ui.isRecording) {
            Modifier
                .pointerInput(Unit) {
                    var accV = 0f
                    val threshold = 120f
                    val cooldownMs = 350L
                    var lastFlip = 0L
                    detectVerticalDragGestures(
                        onDragStart = { accV = 0f },
                        onVerticalDrag = { change, dy -> accV += dy; change.consume() },
                        onDragEnd = {
                            val now = System.currentTimeMillis()
                            if (kotlin.math.abs(accV) > threshold && now - lastFlip > cooldownMs) {
                                lastFlip = now; vm.switchCamera()
                            }
                        }
                    )
                }
                .pointerInput(ui.mode) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            if (dx > 0) vm.setMode(CameraUiState.Mode.PHOTO)
                            else if (dx < 0) vm.setMode(CameraUiState.Mode.VIDEO)
                        }
                    )
                }
        } else Modifier

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(frameAspect)   // KHUNG quyết định kích thước preview
            .then(gestureModifier)
    ) {
        AndroidView(
            factory = {
                TextureView(ctx).also { tv ->
                    tvRef = tv
                    vm.attachPreview(tv)
                    // Re-apply ngay sau khi attach để lần đầu không bị sai
                    tv.post {
                        val buf = vm.getPreviewBufferSize()
                        val ratio = if (buf.width > 0 && buf.height > 0)
                            buf.width.toFloat() / buf.height
                        else
                            frameAspect
                        applyCenterCropTo(tv, ratio)
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { tv ->
                // Mỗi lần update từ Compose, đảm bảo scale đúng theo buffer hiện tại
                val buf = vm.getPreviewBufferSize()
                val ratio = if (buf.width > 0 && buf.height > 0)
                    buf.width.toFloat() / buf.height
                else
                    frameAspect
                tv.post { applyCenterCropTo(tv, ratio) }
            }
        )
    }

    LaunchedEffect(
        ui.aspect,
        ui.mode,
        ui.isRecording,
        ui.isFront,
        ui.isPreviewReady,
        tvRef
    ) {
        tvRef?.post {
            val buf = vm.getPreviewBufferSize()
            val ratio = if (buf.width > 0 && buf.height > 0)
                buf.width.toFloat() / buf.height
            else
                frameAspect
            applyCenterCropTo(tvRef!!, ratio)
        }
    }

    DisposableEffect(tvRef) {
        val view = tvRef
        if (view != null) {
            val listener = android.view.View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val buf = vm.getPreviewBufferSize()
                val ratio = if (buf.width > 0 && buf.height > 0)
                    buf.width.toFloat() / buf.height
                else
                    frameAspect
                (v as TextureView).post { applyCenterCropTo(v, ratio) }
            }
            view.addOnLayoutChangeListener(listener)
            onDispose { view.removeOnLayoutChangeListener(listener) }
        } else {
            onDispose { }
        }
    }
}

private fun applyCenterCropTo(tv: TextureView, contentRatio: Float) {
    val vw = tv.width
    val vh = tv.height
    if (vw == 0 || vh == 0 || contentRatio <= 0f) return

    val vr = vw.toFloat() / vh
    val br1 = contentRatio
    val br2 = 1f / contentRatio

    val br = if (kotlin.math.abs(br1 - vr) <= kotlin.math.abs(br2 - vr)) br1 else br2

    val scale = if (br > vr) br / vr else vr / br

    val m = Matrix().apply { setScale(scale, scale, vw / 2f, vh / 2f) }
    tv.setTransform(m)
}


@Composable
private fun AspectMaskAroundFrame(
    aspect: Float,
    showGrid: Boolean,
    modifier: Modifier = Modifier
) {
    if (!showGrid) return
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val (frameW, frameH) =
            if (w / h > aspect) {
                val fh = h
                val fw = fh * aspect
                fw to fh
            } else {
                val fw = w
                val fh = fw / aspect
                fw to fh
            }

        val left = (w - frameW) / 2f
        val top  = (h - frameH) / 2f

        val line = Color.White.copy(alpha = 0.55f)
        val lw = 1.dp.toPx()

        val x1 = left + frameW / 3f
        val x2 = left + frameW * 2f / 3f
        val y1 = top + frameH / 3f
        val y2 = top + frameH * 2f / 3f

        drawLine(line, Offset(x1, top),            Offset(x1, top + frameH), strokeWidth = lw)
        drawLine(line, Offset(x2, top),            Offset(x2, top + frameH), strokeWidth = lw)
        drawLine(line, Offset(left, y1),           Offset(left + frameW, y1), strokeWidth = lw)
        drawLine(line, Offset(left, y2),           Offset(left + frameW, y2), strokeWidth = lw)
    }
}

@Composable
private fun AspectSelector(
    current: Aspect, onSelect: (Aspect) -> Unit, ui: CameraUiState
) {
    val options = if (ui.mode == CameraUiState.Mode.VIDEO) {
        listOf(Aspect.RATIO_16_9, Aspect.RATIO_1_1)
    } else {
        listOf(Aspect.RATIO_16_9, Aspect.RATIO_3_4, Aspect.RATIO_1_1)
    }
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .clip(CircleShape)
            .background(Color(0x33000000))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { asp ->
            val selected = asp == current
            val scale by animateFloatAsState(
                if (selected) 1.0f else 0.94f, tween(180), label = ""
            )
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
        if (!ui.isFront) {
            AssistButton(
                text = if (ui.flashEnabled) "Flash On" else "Flash Off", onClick = onToggleFlash
            )
            Spacer(Modifier.width(28.dp))
        } else {
            Spacer(Modifier.width(28.dp))
        }
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
