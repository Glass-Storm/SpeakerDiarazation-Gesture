package com.google.mediapipe.examples.gesturerecognizer

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GestureBackgroundRunner @JvmOverloads constructor(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val listener: Listener,
    private val cameraFacing: Int = CameraSelector.LENS_FACING_BACK,
    private val minGestureScore: Float = 0.65f,
    private val stableFramesRequired: Int = 2,
    private val resetAfterNoGestureMs: Long = 700L,
) : GestureRecognizerHelper.GestureRecognizerListener {

    interface Listener {
        fun onGesture(label: String)
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var gestureRecognizerHelper: GestureRecognizerHelper? = null
    private var lastEmittedLabel: String? = null
    private var candidateLabel: String? = null
    private var candidateCount: Int = 0
    private var noGestureSinceMs: Long = 0L

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true

        backgroundExecutor = Executors.newSingleThreadExecutor()

        gestureRecognizerHelper = GestureRecognizerHelper(
            runningMode = RunningMode.LIVE_STREAM,
            context = context.applicationContext,
            gestureRecognizerListener = this,
        )

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                try {
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider
                    bindAnalysisUseCase(provider)
                } catch (t: Throwable) {
                    started = false
                    postError("Failed to start camera: ${t.message ?: t.javaClass.simpleName}")
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    fun stop() {
        if (!started) return
        started = false

        try {
            cameraProvider?.unbindAll()
        } catch (_: Throwable) {
        }
        cameraProvider = null
        imageAnalyzer = null

        try {
            gestureRecognizerHelper?.clearGestureRecognizer()
        } catch (_: Throwable) {
        }
        gestureRecognizerHelper = null

        backgroundExecutor?.shutdown()
        backgroundExecutor = null

        lastEmittedLabel = null
        candidateLabel = null
        candidateCount = 0
        noGestureSinceMs = 0L
    }

    fun isRunning(): Boolean = started

    private fun bindAnalysisUseCase(provider: ProcessCameraProvider) {
        val executor = backgroundExecutor ?: return
        val helper = gestureRecognizerHelper ?: return

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
                analysis.setAnalyzer(executor) { imageProxy ->
                    try {
                        helper.recognizeLiveStream(imageProxy)
                    } catch (t: Throwable) {
                        try {
                            imageProxy.close()
                        } catch (_: Throwable) {
                        }
                        postError("Gesture analysis failed: ${t.message ?: t.javaClass.simpleName}")
                    }
                }
            }

        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalyzer)
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
