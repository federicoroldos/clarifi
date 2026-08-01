package com.clarifi.ui.scan

import android.content.Context
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.clarifi.ui.icons.ClariFiIcons
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * In-app capture, rather than handing off to the system camera.
 *
 * It buys the one thing that matters for receipts: a frame guide showing how much
 * of the paper has to be inside the shot, plus an immediate retake without
 * bouncing between apps.
 */
@Composable
fun CameraCapture(
    onCaptured: (Uri) -> Unit,
    onCancel: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PreviewView(viewContext).also { previewView ->
                    previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            update = { previewView ->
                val providerFuture = ProcessCameraProvider.getInstance(context)
                providerFuture.addListener({
                    runCatching {
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture,
                        )
                    }.onFailure {
                        onError("Could not open the camera on this device.")
                    }
                }, ContextCompat.getMainExecutor(context))
            },
        )

        // Framing guide: roughly a receipt's aspect, centred.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.82f)
                .height(420.dp)
                .border(2.dp, Color.White.copy(alpha = 0.7f), MaterialTheme.shapes.large),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Fit the whole receipt inside the frame",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
            )
            Text(
                text = "Flat, straight on, and as well lit as you can manage",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.75f),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 36.dp),
        ) {
            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 28.dp),
            ) {
                Icon(ClariFiIcons.Close, contentDescription = "Cancel", tint = Color.White)
            }

            IconButton(
                onClick = {
                    capture(context, imageCapture, onCaptured, onError)
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(76.dp)
                    .background(Color.White, CircleShape),
            ) {
                Icon(
                    imageVector = ClariFiIcons.Camera,
                    contentDescription = "Take photo",
                    tint = Color.Black,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }

    // Release the camera as soon as the screen goes away, or the next open fails.
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }
}

private fun capture(
    context: Context,
    imageCapture: ImageCapture,
    onCaptured: (Uri) -> Unit,
    onError: (String) -> Unit,
) {
    // Written to the cache: the photo is an input to the scan, not something the
    // user asked to keep, so it should not land in their gallery.
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val file = File(context.cacheDir, "receipt-$stamp.jpg")

    imageCapture.takePicture(
        ImageCapture.OutputFileOptions.Builder(file).build(),
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                onCaptured(output.savedUri ?: Uri.fromFile(file))
            }

            override fun onError(exception: ImageCaptureException) {
                onError("The photo could not be saved. Try again.")
            }
        },
    )
}
