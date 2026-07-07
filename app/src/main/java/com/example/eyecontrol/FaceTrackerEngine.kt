package com.example.eyecontrol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
    private var cachedBmp: Bitmap? = null
    private val cachedRotatedBmps = arrayOfNulls<Bitmap>(3)
    private var rotatedBmpIndex = 0

    /** 關閉旗標：stop() 之後分析執行緒不得再碰 landmarker（否則對已釋放的原生物件送資料會 SIGSEGV）。 */
    @Volatile
    private var closed = false

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

            // 1280×720：虹膜在影像中的位移只有幾個像素，解析度太低會讓量化雜訊
            // 被回歸模型放大成全螢幕的游標亂飄
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build()
                )
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
            if (closed) return
            val lm = landmarker ?: return
            val bitmap = toBitmap(it) ?: return
            try {
                // LIVE_STREAM 要求單調遞增的時間戳（毫秒）
                lm.detectAsync(BitmapImageBuilder(bitmap).build(), it.imageInfo.timestamp / 1_000_000)
            } catch (e: Exception) {
                // stop() 競態下 landmarker 可能已關閉；丟掉這幀即可
                if (!closed) Log.e(TAG, "detectAsync 失敗", e)
            }
        }
    }

    /** RGBA_8888 ImageProxy → 已擺正方向的 Bitmap（處理 rowStride padding 與旋轉）。 */
    private fun toBitmap(proxy: ImageProxy): Bitmap? {
        val plane = proxy.planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * proxy.width
        val paddedWidth = proxy.width + rowPadding / pixelStride

        // 複用中間的 padded 緩衝 Bitmap，避免每幀重複配置
        var tempBmp = cachedBmp
        if (tempBmp == null || tempBmp.width != paddedWidth || tempBmp.height != proxy.height) {
            tempBmp = Bitmap.createBitmap(paddedWidth, proxy.height, Bitmap.Config.ARGB_8888)
            cachedBmp = tempBmp
        }

        plane.buffer.rewind() // 確保 buffer 指針在開頭
        tempBmp.copyPixelsFromBuffer(plane.buffer)

        val rotation = proxy.imageInfo.rotationDegrees
        val targetWidth = if (rotation == 90 || rotation == 270) proxy.height else proxy.width
        val targetHeight = if (rotation == 90 || rotation == 270) proxy.width else proxy.height

        // 採用三緩衝循環機制，防止 detectAsync 異步讀取時與寫入產生衝突
        val rotatedIndex = rotatedBmpIndex
        rotatedBmpIndex = (rotatedBmpIndex + 1) % 3

        var rotatedBmp = cachedRotatedBmps[rotatedIndex]
        if (rotatedBmp == null || rotatedBmp.width != targetWidth || rotatedBmp.height != targetHeight) {
            rotatedBmp?.recycle()
            rotatedBmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            cachedRotatedBmps[rotatedIndex] = rotatedBmp
        }

        val canvas = android.graphics.Canvas(rotatedBmp!!)
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

        val m = Matrix()
        // 1. 將 (0,0) 移動到原圖中心
        m.postTranslate(-proxy.width / 2f, -proxy.height / 2f)
        // 2. 進行旋轉
        if (rotation != 0) {
            m.postRotate(rotation.toFloat())
        }
        // 3. 移動到目標圖中心
        m.postTranslate(targetWidth / 2f, targetHeight / 2f)

        // 4. 只裁剪繪製有效範圍，排除 Row Padding
        val srcRect = android.graphics.Rect(0, 0, proxy.width, proxy.height)
        val dstRect = android.graphics.RectF(0f, 0f, proxy.width.toFloat(), proxy.height.toFloat())

        canvas.save()
        canvas.concat(m)
        canvas.drawBitmap(tempBmp, srcRect, dstRect, null)
        canvas.restore()

        return rotatedBmp
    }

    fun stop() {
        // 順序很重要：先立旗標擋新幀 → 停相機 → 等分析執行緒清空 → 最後才釋放原生物件
        closed = true
        cameraProvider?.unbindAll()
        cameraProvider = null
        analysisExecutor?.let {
            it.shutdown()
            runCatching { it.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        }
        analysisExecutor = null
        landmarker?.close()
        landmarker = null
        
        // 釋放複用的 Bitmap 緩衝
        cachedBmp?.recycle()
        cachedBmp = null

        // 釋放三緩衝
        for (i in cachedRotatedBmps.indices) {
            cachedRotatedBmps[i]?.recycle()
            cachedRotatedBmps[i] = null
        }
    }

    companion object {
        private const val TAG = "FaceTrackerEngine"
    }
}
