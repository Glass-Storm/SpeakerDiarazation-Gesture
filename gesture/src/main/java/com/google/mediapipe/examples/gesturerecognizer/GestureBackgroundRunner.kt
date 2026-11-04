package com.google.mediapipe.examples.gesturerecognizer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class GestureBackgroundRunner @JvmOverloads constructor(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val listener: Listener,
    private val cameraFacing: Int = CameraSelector.LENS_FACING_BACK,
    private val minGestureScore: Float = 0.65f,
    private val stableFramesRequired: Int = 2,
    private val resetAfterNoGestureMs: Long = 700L,
    private val jpegIntervalMs: Long = DEFAULT_JPEG_INTERVAL_MS,
    private val jpegTargetWidth: Int = DEFAULT_JPEG_WIDTH,
    private val jpegTargetHeight: Int = DEFAULT_JPEG_HEIGHT,
) : GestureRecognizerHelper.GestureRecognizerListener {

    interface Listener {
        fun onGesture(label: String)
        fun onError(message: String)
    }

    /**
     * Receives upright, downscaled JPEG stills for the hub's S3 gesture session.
     *
     * This callback exists so the `:gesture` library never has to import the
     * `:app` module's {@code EcosysStreamClient}: the host app wires this listener
     * to its own transport. Calling [setJpegFrameListener] switches the runner
     * into hub mode: local MediaPipe recognition is suppressed and the camera
     * emits stills instead.
     */
    interface JpegFrameListener {
        fun onJpegFrame(jpeg: ByteArray, ptsMs: Long, width: Int, height: Int)
        fun onJpegError(message: String)
    }

    companion object {
        private const val TAG = "GestureBackgroundRunner"
        const val DEFAULT_JPEG_INTERVAL_MS = 300L
        const val DEFAULT_JPEG_WIDTH = 320
        const val DEFAULT_JPEG_HEIGHT = 240
        private const val JPEG_QUALITY = 80
        private const val JPEG_ERROR_MIN_INTERVAL_MS = 3_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundExecutor: ExecutorService? = null
    private var jpegCaptureExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    @Volatile
    private var jpegFrameListener: JpegFrameListener? = null
    @Volatile
    private var jpegFallbackActive = false
    @Volatile
    private var jpegCaptureInFlight = false
    @Volatile
    private var lastJpegFallbackMs = 0L
    @Volatile
    private var lastJpegErrorAtMs = 0L
    private var gestureRecognizerHelper: GestureRecognizerHelper? = null
    private var lastEmittedLabel: String? = null
    private var candidateLabel: String? = null
    private var candidateCount: Int = 0
    private var noGestureSinceMs: Long = 0L

    private val jpegTick = object : Runnable {
        override fun run() {
            if (!started || jpegFrameListener == null) return
            val capture = imageCapture ?: return
            if (jpegCaptureInFlight) {
                scheduleJpegTick()
                return
            }
            val executor = jpegCaptureExecutor ?: backgroundExecutor
            if (executor == null) return
            jpegCaptureInFlight = true
            try {
                capture.takePicture(
                    executor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                emitJpeg(encodeJpeg(image))
                            } catch (t: Throwable) {
                                postJpegError("影像編碼失敗：${t.message ?: t.javaClass.simpleName}")
                            } finally {
                                try {
                                    image.close()
                                } catch (_: Throwable) {
                                }
                                jpegCaptureInFlight = false
                                scheduleJpegTick()
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            jpegCaptureInFlight = false
                            postJpegError("影像擷取失敗：${exception.message ?: "unknown"}")
                            scheduleJpegTick()
                        }
                    }
                )
            } catch (t: Throwable) {
                jpegCaptureInFlight = false
                postJpegError("影像擷取失敗：${t.message ?: t.javaClass.simpleName}")
                scheduleJpegTick()
            }
        }
    }

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true

        backgroundExecutor = Executors.newSingleThreadExecutor()
        jpegCaptureExecutor = Executors.newSingleThreadExecutor()

        if (jpegFrameListener == null) {
            gestureRecognizerHelper = GestureRecognizerHelper(
                runningMode = RunningMode.LIVE_STREAM,
                context = context.applicationContext,
                gestureRecognizerListener = this,
                mirrorHorizontally = (cameraFacing == CameraSelector.LENS_FACING_FRONT),
            )
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                try {
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider
                    bindUseCases(provider)
                } catch (t: Throwable) {
                    started = false
                    postError("Failed to start camera: ${t.message ?: t.javaClass.simpleName}")
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    /**
     * Switches the runner between local recognition (a `null` listener) and hub
     * S3 uplink mode. Safe before or after [start]; a live runner rebinds so the
     * requested use-case set takes effect immediately.
     */
    fun setJpegFrameListener(listener: JpegFrameListener?) {
        jpegFrameListener = listener
        if (!started) return
        val provider = cameraProvider ?: return
        try {
            bindUseCases(provider)
        } catch (t: Throwable) {
            postError("Failed to reconfigure camera: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun stop() {
        if (!started) return
        started = false

        mainHandler.removeCallbacks(jpegTick)
        jpegCaptureInFlight = false

        try {
            cameraProvider?.unbindAll()
        } catch (_: Throwable) {
        }
        cameraProvider = null
        imageAnalyzer = null
        imageCapture = null
        jpegFallbackActive = false
        lastJpegFallbackMs = 0L
        lastJpegErrorAtMs = 0L

        try {
            gestureRecognizerHelper?.clearGestureRecognizer()
        } catch (_: Throwable) {
        }
        gestureRecognizerHelper = null

        backgroundExecutor?.shutdown()
        backgroundExecutor = null
        jpegCaptureExecutor?.shutdown()
        jpegCaptureExecutor = null

        lastEmittedLabel = null
        candidateLabel = null
        candidateCount = 0
        noGestureSinceMs = 0L
    }

    fun isRunning(): Boolean = started

    private fun bindUseCases(provider: ProcessCameraProvider) {
        val executor = backgroundExecutor ?: return
        val hubUplink = jpegFrameListener != null

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(cameraFacing)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(getDisplayRotation())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(executor) { imageProxy -> handleAnalysisFrame(imageProxy) }
            }

        val capture = if (hubUplink) buildImageCapture() else null

        provider.unbindAll()
        if (capture != null) {
            try {
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalyzer, capture)
                imageCapture = capture
                jpegFallbackActive = false
                Log.i(
                    TAG,
                    "bound ImageAnalysis + ImageCapture; JPEG stills every ${jpegIntervalMs}ms"
                )
                startJpegLoop()
                return
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "ImageAnalysis + ImageCapture co-bind rejected; falling back to analysis JPEGs",
                    t
                )
                imageCapture = null
                jpegFallbackActive = true
                provider.unbindAll()
            }
        } else {
            imageCapture = null
            jpegFallbackActive = false
        }
        provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalyzer)
    }

    private fun handleAnalysisFrame(imageProxy: ImageProxy) {
        val hubUplink = jpegFrameListener != null
        try {
            if (hubUplink) {
                if (jpegFallbackActive) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastJpegFallbackMs >= jpegIntervalMs) {
                        lastJpegFallbackMs = now
                        emitJpeg(encodeJpeg(imageProxy))
                    }
                }
                imageProxy.close()
                return
            }
            val helper = gestureRecognizerHelper
            if (helper == null) {
                imageProxy.close()
            } else {
                helper.recognizeLiveStream(imageProxy)
            }
        } catch (t: Throwable) {
            try {
                imageProxy.close()
            } catch (_: Throwable) {
            }
            if (hubUplink) {
                postJpegError("影像處理失敗：${t.message ?: t.javaClass.simpleName}")
            } else {
                postError("Gesture analysis failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun buildImageCapture(): ImageCapture? {
        return try {
            ImageCapture.Builder()
                .setTargetRotation(getDisplayRotation())
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(Size(jpegTargetWidth * 2, jpegTargetHeight * 2))
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "ImageCapture is unavailable on this device", t)
            postJpegError("相機不支援靜態影像擷取：${t.message ?: t.javaClass.simpleName}")
            null
        }
    }

    private fun startJpegLoop() {
        if (jpegFrameListener == null || imageCapture == null) return
        mainHandler.removeCallbacks(jpegTick)
        mainHandler.post(jpegTick)
    }

    private fun scheduleJpegTick() {
        mainHandler.removeCallbacks(jpegTick)
        if (started && jpegFrameListener != null && imageCapture != null) {
            mainHandler.postDelayed(jpegTick, jpegIntervalMs)
        }
    }

    private class JpegPayload(
        val jpeg: ByteArray,
        val width: Int,
        val height: Int,
    )

    private fun encodeJpeg(imageProxy: ImageProxy): JpegPayload {
        var bitmap = imageProxy.toBitmap()
        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated =
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        if (bitmap.width > jpegTargetWidth || bitmap.height > jpegTargetHeight) {
            val scale =
                min(
                    jpegTargetWidth.toFloat() / bitmap.width,
                    jpegTargetHeight.toFloat() / bitmap.height
                )
            val scaledWidth = max(1, (bitmap.width * scale).roundToInt())
            val scaledHeight = max(1, (bitmap.height * scale).roundToInt())
            val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        val width = bitmap.width
        val height = bitmap.height
        val output = ByteArrayOutputStream()
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                throw IllegalStateException("Bitmap.compress(JPEG) returned false")
            }
        } finally {
            bitmap.recycle()
        }
        return JpegPayload(output.toByteArray(), width, height)
    }

    private fun emitJpeg(payload: JpegPayload) {
        val target = jpegFrameListener ?: return
        val ptsMs = SystemClock.elapsedRealtime()
        try {
            target.onJpegFrame(payload.jpeg, ptsMs, payload.width, payload.height)
        } catch (t: Throwable) {
            postJpegError("JPEG frame listener failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun postJpegError(message: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastJpegErrorAtMs < JPEG_ERROR_MIN_INTERVAL_MS) return
        lastJpegErrorAtMs = now
        mainHandler.post {
            jpegFrameListener?.onJpegError(message)
        }
    }

    private fun getDisplayRotation(): Int {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display?.rotation ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.rotation
            }
        } catch (_: Throwable) {
            Surface.ROTATION_0
        }
    }

    override fun onResults(resultBundle: GestureRecognizerHelper.ResultBundle) {
        val nowMs = SystemClock.uptimeMillis()
        val result = resultBundle.results.firstOrNull()

        val categories = result
            ?.gestures()
            ?.firstOrNull()

        val top = categories?.maxByOrNull { it.score() }
        val rawLabel = top?.categoryName()?.trim().orEmpty()
        val score = top?.score() ?: 0f

        val isValidGesture = rawLabel.isNotEmpty() && score >= minGestureScore
        if (!isValidGesture) {
            candidateLabel = null
            candidateCount = 0
            if (noGestureSinceMs == 0L) {
                noGestureSinceMs = nowMs
            }
            if (lastEmittedLabel != null && (nowMs - noGestureSinceMs) >= resetAfterNoGestureMs) {
                lastEmittedLabel = null
            }
            return
        }

        noGestureSinceMs = 0L

        if (rawLabel == candidateLabel) {
            candidateCount += 1
        } else {
            candidateLabel = rawLabel
            candidateCount = 1
        }

        if (candidateCount < stableFramesRequired) return
        if (rawLabel == lastEmittedLabel) return

        lastEmittedLabel = rawLabel
        mainHandler.post {
            listener.onGesture(rawLabel)
        }
    }

    override fun onError(error: String, errorCode: Int) {
        postError(error)
    }

    private fun postError(message: String) {
        mainHandler.post {
            listener.onError(message)
        }
    }
}
