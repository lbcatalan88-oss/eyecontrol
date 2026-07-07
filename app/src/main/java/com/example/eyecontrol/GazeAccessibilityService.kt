package com.example.eyecontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlin.math.hypot
import kotlin.math.min

/**
 * 眼控核心服務：
 *   前鏡頭(FaceTrackerEngine) → GazeModel 對應螢幕座標 → OneEuro 平滑
 *   → 全螢幕覆蓋層畫游標 → 凝視停留(dwell)觸發 dispatchGesture 點擊。
 *
 * 覆蓋層使用 TYPE_ACCESSIBILITY_OVERLAY 且不可觸控，注入的手勢會直接穿透到底下的 App。
 * 實作 LifecycleOwner 是為了讓 CameraX 能綁定在 Service 上。
 */
class GazeAccessibilityService : AccessibilityService(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var engine: FaceTrackerEngine? = null
    private var model: GazeModel? = null
    private var cursorView: CursorOverlayView? = null

    private val filterX = OneEuroFilter(minCutoff = 0.5, beta = 0.005)
    private val filterY = OneEuroFilter(minCutoff = 0.5, beta = 0.005)

    /** 校正頁開啟期間暫停追蹤（相機同程序只有一路，兩邊搶會互踢）。 */
    @Volatile
    private var pausedForCalibration = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // 凝視停留狀態
    private var anchorX = 0f
    private var anchorY = 0f
    private var dwellStart = 0L
    private var cooldownUntil = 0L

    private var screenW = 0
    private var screenH = 0

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        instance = this

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        screenW = bounds.width()
        screenH = bounds.height()

        addCursorOverlay(wm)
        startEngine()
    }

    /** 建立並啟動追蹤引擎；同時（重新）載入校正模型。校正後恢復也走這裡。 */
    private fun startEngine() {
        if (pausedForCalibration) return
        model = GazeModel.load(this)
        if (model == null) {
            Log.w(TAG, "尚未校正，游標無法運作——請先在 App 內完成校正")
        }
        filterX.reset()
        filterY.reset()
        engine = FaceTrackerEngine(this) { sample -> onSample(sample) }
        engine?.start(this)
    }

    private fun addCursorOverlay(wm: WindowManager) {
        val view = CursorOverlayView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // 不可觸控、不可聚焦：注入的手勢與使用者的手指都能穿透
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        wm.addView(view, lp)
        cursorView = view
    }

    /** 每一幀的處理（背景執行緒進來，UI 更新丟回主執行緒）。 */
    private fun onSample(sample: GazeSample?) {
        if (pausedForCalibration) return
        val m = model ?: return
        val cursor = cursorView ?: return

        if (sample == null) {
            // 偵測不到臉：游標留在原地變灰，讓使用者知道是「臉不見了」而不是當機
            cursor.post { cursor.setFaceLost() }
            return
        }
        // 閉眼/眨眼期間虹膜資料不可靠：凍結游標、暫停 dwell 累積
        if (sample.leftOpen < BLINK_THRESHOLD || sample.rightOpen < BLINK_THRESHOLD) return

        val (rawX, rawY) = m.predict(sample.features)
        val t = sample.timestampMs / 1000.0
        val x = filterX.filter(rawX, t).toFloat().coerceIn(0f, screenW - 1f)
        val y = filterY.filter(rawY, t).toFloat().coerceIn(0f, screenH - 1f)

        val now = SystemClock.elapsedRealtime()
        var progress = 0f

        if (hypot(x - anchorX, y - anchorY) > DWELL_RADIUS_PX) {
            // 視線移出停留區：重新定錨
            anchorX = x; anchorY = y; dwellStart = now
        } else if (now >= cooldownUntil) {
            progress = min((now - dwellStart) / DWELL_TIME_MS.toFloat(), 1f)
            if (progress >= 1f) {
                performTap(anchorX, anchorY)
                cooldownUntil = now + COOLDOWN_MS
                dwellStart = now
                progress = 0f
            }
        }

        val p = progress
        cursor.post { cursor.setCursor(Triple(x, y, p)) }
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                cursorView?.post { cursorView?.flashClick() }
            }
        }, null)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        engine?.stop()
        engine = null
        cursorView?.let {
            runCatching {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it)
            }
        }
        cursorView = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /** 全螢幕透明覆蓋層：畫游標圓點與 dwell 進度環。 */
    private class CursorOverlayView(context: Context) : View(context) {
        private var cursor: Triple<Float, Float, Float>? = null // x, y, dwellProgress
        private var faceLost = false
        private var flashUntil = 0L

        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 79, 195, 247)
        }
        private val lostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 158, 158, 158); style = Paint.Style.STROKE; strokeWidth = 5f
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 5f
        }
        private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 255, 235, 59); style = Paint.Style.STROKE; strokeWidth = 8f
        }

        fun setCursor(c: Triple<Float, Float, Float>?) {
            cursor = c
            faceLost = false
            invalidate()
        }

        /** 偵測不到臉：游標留在最後位置，改畫灰色空心圈。 */
        fun setFaceLost() {
            faceLost = true
            invalidate()
        }

        fun flashClick() {
            flashUntil = SystemClock.elapsedRealtime() + 250
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val c = cursor ?: return
            val (x, y, progress) = c
            if (faceLost) {
                canvas.drawCircle(x, y, 16f, lostPaint)
                return
            }
            canvas.drawCircle(x, y, 14f, dotPaint)
            if (progress > 0f) {
                val r = 26f
                canvas.drawArc(x - r, y - r, x + r, y + r, -90f, 360f * progress, false, ringPaint)
            }
            if (SystemClock.elapsedRealtime() < flashUntil) {
                canvas.drawCircle(x, y, 40f, flashPaint)
                postInvalidateDelayed(50)
            }
        }
    }

    companion object {
        /** 目前存活的服務實例（無障礙服務為單例）。校正頁用它做相機交接。 */
        @Volatile
        private var instance: GazeAccessibilityService? = null

        /**
         * 校正頁啟動前呼叫：請服務放開相機，完全釋放後在主執行緒回呼 onReleased。
         * 服務未啟用時直接回呼。這是根除「服務與校正搶相機」競態的關鍵——
         * 校正頁務必等到 onReleased 才綁定自己的相機。
         */
        fun requestPauseForCalibration(onReleased: () -> Unit) {
            val svc = instance
            if (svc == null) {
                onReleased()
                return
            }
            svc.pausedForCalibration = true
            // engine.stop() 會阻塞等待分析執行緒排空，丟到背景執行緒避免卡主執行緒
            Thread {
                svc.engine?.stop()
                svc.engine = null
                svc.mainHandler.post {
                    svc.cursorView?.setCursor(null)
                    onReleased()
                }
            }.start()
        }

        /** 校正頁結束後呼叫：服務重新載入校正結果並接回相機。 */
        fun resumeAfterCalibration() {
            val svc = instance ?: return
            svc.mainHandler.post {
                svc.pausedForCalibration = false
                svc.startEngine()
            }
        }

        private const val TAG = "GazeService"
        // 與校正頁一致：太高會把「往下看時眼皮半垂」誤判成眨眼，游標下不去
        private const val BLINK_THRESHOLD = 0.09
        private const val DWELL_RADIUS_PX = 110f  // 視線在此半徑內視為「停留」
        private const val DWELL_TIME_MS = 1000L   // 停留 1 秒觸發點擊
        private const val COOLDOWN_MS = 1200L     // 點擊後的不應期，避免連點
    }
}
