package com.example.eyecontrol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 前鏡頭 → MediaPipe Face Landmarker → GazeSample 的串流引擎。
 * CalibrationActivity 與 GazeAccessibilityService 共用；呼叫端只需提供
 * LifecycleOwner（Activity 本身，或 Service 自建的 LifecycleRegistry）。
 *
 * onSample 會在背景執行緒被呼叫；偵測不到臉時傳 null。
 */
class FaceTrackerEngine(
    private val context: Context,
    private val onSample: (GazeSample?) -> Unit,
) {
    private var landmarker: FaceLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null

    fun start(lifecycleOwner: LifecycleOwner) {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("face_landmarker.task")
                    .build()
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setResultListener { result: FaceLandmarkerResult, _ ->
                val face = result.faceLandmarks().firstOrNull()
                if (face == null) {
                    onSample(null)
                } else {
                    onSample(GazeFeatureExtractor.extract(face, result.timestampMs()))
                }
            }
            .setErrorListener { e -> Log.e(TAG, "FaceLandmarker 錯誤", e) }
            .build()
        landmarker = FaceLandmarker.createFromOptions(context, options)

        analysisExecutor = Executors.newSingleThreadExecutor()

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor!!) { proxy -> analyze(proxy) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis,
                )
            } catch (e: Exception) {
                Log.e(TAG, "相機綁定失敗", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(proxy: ImageProxy) {
        proxy.use {
            val lm = landmarker ?: return
            val bitmap = toBitmap(it) ?: return
            // LIVE_STREAM 要求單調遞增的時間戳（毫秒）
            lm.detectAsync(BitmapImageBuilder(bitmap).build(), it.imageInfo.timestamp / 1_000_000)
        }
    }

    /** RGBA_8888 ImageProxy → 已擺正方向的 Bitmap（處理 rowStride padding 與旋轉）。 */
    private fun toBitmap(proxy: ImageProxy): Bitmap? {
        val plane = proxy.planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * proxy.width
        val paddedWidth = proxy.width + rowPadding / pixelStride

        var bmp = Bitmap.createBitmap(paddedWidth, proxy.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        if (paddedWidth != proxy.width) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, proxy.width, proxy.height)
        }
        val rotation = proxy.imageInfo.rotationDegrees
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return bmp
    }

    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
        landmarker?.close()
        landmarker = null
    }

    companion object {
        private const val TAG = "FaceTrackerEngine"
    }
}
