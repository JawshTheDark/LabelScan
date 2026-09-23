package dev.jawsh.labelscan.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.jawsh.labelscan.AppViewModel
import dev.jawsh.labelscan.Screen
import dev.jawsh.labelscan.data.Photos
import java.util.concurrent.Executors

@Composable
fun ScanScreen(vm: AppViewModel, modifier: Modifier, orderSheet: Boolean = false) {
    if (orderSheet) {
        OrderSheetScan(vm, modifier)
        return
    }
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { if (orderSheet) vm.processOrderSheetUri(it) else vm.processUri(it) }
    }
    val pickPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraView(vm, pickPhoto, orderSheet)
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (orderSheet) "Camera access is needed to photograph order sheets."
                    else "Camera access is needed to photograph labels.",
                    color = Color.White, textAlign = TextAlign.Center,
                )
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                OutlinedButton(onClick = pickPhoto) { Text("Pick an existing photo", color = Color.White) }
            }
        }
        IconButton(onClick = { vm.screen = Screen.Library }, Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
        }
        if (vm.busy) {
            Column(
                Modifier.fillMaxSize().background(Color(0xB0000000)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(if (orderSheet) "Reading order sheet…" else "Reading label…", color = Color.White)
            }
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun CameraView(vm: AppViewModel, pickPhoto: () -> Unit, orderSheet: Boolean) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx) }
    val capture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }
    val worker = remember { Executors.newSingleThreadExecutor() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }

    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            provider.unbindAll()
            camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            runCatching { future.get().unbindAll() }
            worker.shutdown()
        }
    }

    // Tap to focus: small label print needs a sharp photo.
    previewView.setOnTouchListener { v, e ->
        if (e.action == MotionEvent.ACTION_UP) {
            val point = previewView.meteringPointFactory.createPoint(e.x, e.y)
            camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
            v.performClick()
        }
        true
    }

    fun shoot() {
        if (vm.busy) return
        capture.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val bitmap = image.use { Photos.rotate(Photos.scaleDown(it.toBitmap(), Photos.OCR_MAX_PX), rotation) }
                ContextCompat.getMainExecutor(ctx).execute {
                    if (orderSheet) vm.processOrderSheet(bitmap) else vm.process(bitmap)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                vm.messages.tryEmit("Capture failed: ${exception.message}")
            }
        })
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.85f)
                .height(260.dp)
                .border(BorderStroke(2.dp, Color(0xCCFFB300)), RoundedCornerShape(12.dp)),
        )
        Text(
            if (orderSheet) "Fill the frame with one order or throwaway sheet.\nHold it upright and flat. Tap to focus."
            else "Fill the frame with the label — any angle works.\nTap to focus.",
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp)
                .background(Color(0x80000000), RoundedCornerShape(8.dp)).padding(8.dp),
        )
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = pickPhoto) { Text("PHOTOS", color = Color.White) }
            Box(
                Modifier
                    .size(76.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .background(Color.White, CircleShape)
                    .clickable { shoot() },
            )
            TextButton(onClick = {
                torch = !torch
                camera?.cameraControl?.enableTorch(torch)
            }) { Text(if (torch) "LIGHT ON" else "LIGHT", color = if (torch) Color(0xFFFFB300) else Color.White) }
        }
    }
}
