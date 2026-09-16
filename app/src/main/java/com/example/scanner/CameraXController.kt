package com.example.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Holds the live CameraX session state that the Compose layer observes.
 */
data class CameraSessionState(
    val isCameraReady: Boolean = false,
    val isFlashOn: Boolean = false,
    val isCapturing: Boolean = false,
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val zoomScale: Float = 1f,
    val supported: Boolean = true,
    val errorMessage: String? = null
) {
    val canCapture: Boolean get() = isCameraReady && !isCapturing && supported
}

/**
 * Encapsulates all CameraX plumbing (provider, preview, image capture, torch, zoom)
 * so the Compose layer never touches [ProcessCameraProvider] directly.
 *
 * Construct one per scanning screen, call [bind] when the PreviewView is ready and
 * [unbind] when the screen leaves the composition.
 */
class CameraXController(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var provider: ProcessCameraProvider? = null

    private val _state = MutableStateFlow(CameraSessionState())
    val state: StateFlow<CameraSessionState> = _state.asStateFlow()

    private fun update(transform: (CameraSessionState) -> CameraSessionState) {
        _state.value = transform(_state.value)
    }

    /**
     * Binds preview + image-capture use cases to [lifecycleOwner] and wires the
     * preview surface. Must be called from the main dispatcher.
     */
    suspend fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lensFacing: Int = CameraSelector.LENS_FACING_BACK
    ) = withContext(Dispatchers.Main) {
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).await()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            val targetRotation = previewView.display?.rotation
            val preview = Preview.Builder()
                .apply { targetRotation?.let { setTargetRotation(it) } }
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            // Maximize quality: scans are re-processed downstream, so keep all the pixels.
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .apply { targetRotation?.let { setTargetRotation(it) } }
                .build()

            cameraProvider.unbindAll()
            val boundCamera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                capture
            )

            this@CameraXController.provider = cameraProvider
            this@CameraXController.preview = preview
            this@CameraXController.imageCapture = capture
            this@CameraXController.camera = boundCamera

            update {
                it.copy(
                    isCameraReady = true,
                    lensFacing = lensFacing,
                    supported = true,
                    errorMessage = null,
                    isFlashOn = boundCamera.cameraInfo.torchState.value == TorchState.ON
                )
            }
        } catch (e: Exception) {
            update {
                it.copy(
                    isCameraReady = false,
                    supported = false,
                    errorMessage = e.localizedMessage ?: "This device has no compatible camera."
                )
            }
        }
    }

    /** Releases all camera use cases. Safe to call repeatedly. */
    fun unbind() {
        provider?.unbindAll()
        provider = null
        imageCapture = null
        preview = null
        camera = null
        update { it.copy(isCameraReady = false, isCapturing = false) }
    }

    /**
     * Takes a high-quality still. The raw [ImageProxy] is decoded to a Bitmap and
     * rotated upright using the sensor orientation, then handed to [onResult].
     */
    suspend fun capture(
        onResult: (Bitmap) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.Main) {
        val capture = imageCapture
        if (capture == null) {
            onError("Camera is not ready yet.")
            return@withContext
        }

        update { it.copy(isCapturing = true) }

        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bitmap = image.toBitmap()
                        val corrected = bitmap.rotateBy(image.imageInfo.rotationDegrees)
                        if (corrected !== bitmap) bitmap.recycle()
                        onResult(corrected)
                    } catch (e: Exception) {
                        onError(e.localizedMessage ?: "Failed to process captured frame.")
                    } finally {
                        image.close()
                        update { it.copy(isCapturing = false) }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    update { it.copy(isCapturing = false) }
                    onError(exception.localizedMessage ?: "Camera capture failed.")
                }
            }
        )
    }

    /**
     * Writes an upright bitmap to [directory] as a JPEG and returns its Uri.
     * Uses a plain file Uri — the pages stay inside app-private cache, which the
     * app can read directly without a FileProvider round trip.
     */
    suspend fun saveRawCapture(
        bitmap: Bitmap,
        directory: File,
        quality: Int = 95
    ): Uri = withContext(Dispatchers.IO) {
        if (!directory.exists()) directory.mkdirs()
        val file = File(directory, "raw_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        Uri.fromFile(file)
    }

    /** Toggles the torch when the device advertises a flash unit. */
    fun toggleTorch() {
        val cam = camera ?: return
        val info = cam.cameraInfo
        if (!info.hasFlashUnit()) return
        val newState = info.torchState.value != TorchState.ON
        cam.cameraControl.enableTorch(newState)
        update { it.copy(isFlashOn = newState) }
    }

    /** Sets linear zoom in the 1f..5f range. */
    fun setZoom(scale: Float) {
        camera?.cameraControl?.setZoomRatio(scale.coerceIn(1f, 5f))
        update { it.copy(zoomScale = scale) }
    }

    /** True when [lensFacing] is reported by the device. */
    suspend fun hasCamera(lensFacing: Int = CameraSelector.LENS_FACING_BACK): Boolean =
        withContext(Dispatchers.IO) {
            try {
                ProcessCameraProvider.getInstance(context).await()
                    .hasCamera(CameraSelector.Builder().requireLensFacing(lensFacing).build())
            } catch (e: Exception) {
                false
            }
        }

    private fun Bitmap.rotateBy(degrees: Int): Bitmap {
        if (degrees == 0) return this
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        // [createBitmap] may return this same instance for an identity transform,
        // which is why the caller compares the result by reference before recycling.
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Could not decode captured frame.")
    }

    /**
     * Awaits a Guava [com.google.common.util.concurrent.ListenableFuture] without
     * blocking the main thread. CameraX ships Guava as a transitive dependency.
     */
    private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(): T =
        suspendCancellableCoroutine { cont ->
            addListener(
                {
                    try {
                        cont.resume(get())
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        }
}
