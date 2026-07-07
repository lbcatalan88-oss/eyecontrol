package com.example.eyecontrol

import android.graphics.RectF
import android.widget.Toast
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

    enum class ActionMode {
        TAP, DOUBLE_TAP, LONG_PRESS, SWIPE, PAUSED
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var engine: FaceTrackerEngine? = null
    private var model: GazeModel? = null
    private var cursorView: CursorOverlayView? = null
    private var controlView: ControlPanelView? = null

    private val filterX = OneEuroFilter(minCutoff = 0.2, beta = 0.015)
    private val filterY = OneEuroFilter(minCutoff = 0.2, beta = 0.015)

    /** 校正頁開啟期間暫停追蹤（相機同程序只有一路，兩邊搶會互踢）。 */
    @Volatile
    private var pausedForCalibration = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // 模式與手勢狀態
    private var currentMode = ActionMode.TAP
    private var swipeStartPoint: Pair<Float, Float>? = null

    // 控制面板注視狀態
    private var hoverBtnIndex = -1
    private var hoverStart = 0L

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
        addControlPanel(wm)
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

    private fun addControlPanel(wm: WindowManager) {
        val view = ControlPanelView(this)
        val lp = WindowManager.LayoutParams(
            180, // 寬度 180 像素
            700, // 高度 700 像素
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // 不可觸控、不可聚焦
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        wm.addView(view, lp)
        controlView = view
    }

    // 上一幀的停留進度
    private var currentProgress = 0f

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

        // 動態平滑調整：若上一幀在進行停留點擊，則極大化濾波平滑度以鎖定游標；若在移動，則提高反應跟手度。
        if (currentProgress > 0f) {
            filterX.updateParams(minCutoff = 0.04, beta = 0.005)
            filterY.updateParams(minCutoff = 0.04, beta = 0.005)
        } else {
            filterX.updateParams(minCutoff = 0.22, beta = 0.018)
            filterY.updateParams(minCutoff = 0.22, beta = 0.018)
        }

        val (rawX, rawY) = m.predict(sample.features, screenW, screenH)
        val t = sample.timestampMs / 1000.0
        val x = filterX.filter(rawX, t).toFloat().coerceIn(0f, screenW - 1f)
        val y = filterY.filter(rawY, t).toFloat().coerceIn(0f, screenH - 1f)

        val edgeSize = 50f
        val isAtLeft = x < edgeSize
        val isAtRight = x > screenW - edgeSize
        val isAtTop = y < edgeSize
        val isAtBottom = y > screenH - edgeSize
        val isAtEdge = isAtLeft || isAtRight || isAtTop || isAtBottom

        val now = SystemClock.elapsedRealtime()
        var progress = 0f

        // 控制面板區域檢測 (寬 180 像素，高 700 像素，靠右置中)
        val panelWidth = 180f
        val panelHeight = 700f
        val panelLeft = screenW - panelWidth
        val panelTop = (screenH - panelHeight) / 2f
        val panelBottom = panelTop + panelHeight

        val inPanel = x >= panelLeft && y >= panelTop && y <= panelBottom

        if (inPanel) {
            val relativeY = y - panelTop
            val btnHeight = panelHeight / 5f
            val btnIndex = (relativeY / btnHeight).toInt().coerceIn(0, 4)

            // 如果是暫停狀態，只有注視最後一個按鈕（重啟）才有反應
            if (currentMode == ActionMode.PAUSED && btnIndex != 4) {
                controlView?.post {
                    controlView?.setHover(-1, 0f)
                }
            } else {
                if (btnIndex != hoverBtnIndex) {
                    hoverBtnIndex = btnIndex
                    hoverStart = now
                }
                val hp = min((now - hoverStart) / CONTROL_DWELL_MS.toFloat(), 1f)
                controlView?.post {
                    controlView?.setHover(btnIndex, hp)
                }
                if (hp >= 1f) {
                    // 觸發切換
                    val targetItem = controlView?.items?.get(btnIndex)
                    if (targetItem != null) {
                        if (currentMode == ActionMode.PAUSED) {
                            currentMode = ActionMode.TAP
                        } else {
                            if (targetItem.mode == ActionMode.PAUSED) {
                                currentMode = ActionMode.PAUSED
                            } else {
                                currentMode = targetItem.mode
                            }
                        }
                        controlView?.activeMode = currentMode
                        controlView?.post { controlView?.invalidate() }
                    }
                    cooldownUntil = now + COOLDOWN_MS
                    hoverBtnIndex = -1
                    hoverStart = now
                }
            }
            // 在面板上時，不累積螢幕點擊的 dwell 進度
            anchorX = x; anchorY = y; dwellStart = now
        } else {
            // 不在面板上，清除面板 hover 狀態
            if (hoverBtnIndex != -1) {
                hoverBtnIndex = -1
                controlView?.post {
                    controlView?.setHover(-1, 0f)
                }
            }

            // 如果目前是暫停狀態，游標不累積點擊，且不顯示游標（或者顯示半透明灰色）
            if (currentMode == ActionMode.PAUSED) {
                cursor.post { cursor.setCursor(null) }
                return
            }

            // 處理螢幕點擊與滑動的 dwell 邏輯
            if (hypot(x - anchorX, y - anchorY) > DWELL_RADIUS_PX) {
                anchorX = x; anchorY = y; dwellStart = now
            } else if (now >= cooldownUntil) {
                progress = min((now - dwellStart) / DWELL_TIME_MS.toFloat(), 1f)
                if (progress >= 1f) {
                    if (isAtEdge) {
                        performEdgeAction(isAtLeft, isAtRight, isAtTop, isAtBottom)
                    } else {
                        triggerActionAt(anchorX, anchorY)
                    }
                    cooldownUntil = now + COOLDOWN_MS
                    dwellStart = now
                    progress = 0f
                }
            }
        }

        currentProgress = progress
        cursor.post { cursor.setCursor(Triple(x, y, progress)) }
    }

    private fun triggerActionAt(x: Float, y: Float) {
        when (currentMode) {
            ActionMode.TAP -> {
                performTap(x, y)
            }
            ActionMode.DOUBLE_TAP -> {
                performDoubleTap(x, y)
                currentMode = ActionMode.TAP
                controlView?.activeMode = ActionMode.TAP
                controlView?.invalidate()
            }
            ActionMode.LONG_PRESS -> {
                performLongPress(x, y)
                currentMode = ActionMode.TAP
                controlView?.activeMode = ActionMode.TAP
                controlView?.invalidate()
            }
            ActionMode.SWIPE -> {
                val start = swipeStartPoint
                if (start == null) {
                    swipeStartPoint = x to y
                    mainHandler.post {
                        Toast.makeText(this, "起點已選取，請注視滑動終點", Toast.LENGTH_SHORT).show()
                    }
                    controlView?.swipeState = 1 // 1 代表已選起點
                    controlView?.invalidate()
                } else {
                    performSwipe(start.first, start.second, x, y)
                    swipeStartPoint = null
                    currentMode = ActionMode.TAP
                    controlView?.swipeState = 0
                    controlView?.activeMode = ActionMode.TAP
                    controlView?.invalidate()
                }
            }
            else -> {}
        }
    }

    /** 執行邊緣區域觸發的特殊手勢：返回、首頁與向上/下捲動 */
    private fun performEdgeAction(left: Boolean, right: Boolean, top: Boolean, bottom: Boolean) {
        when {
            top -> {
                // 頂部邊緣停留：返回
                performGlobalAction(GLOBAL_ACTION_BACK)
                cursorView?.post { cursorView?.flashClick() }
            }
            bottom -> {
                // 底部邊緣停留：首頁
                performGlobalAction(GLOBAL_ACTION_HOME)
                cursorView?.post { cursorView?.flashClick() }
            }
            left -> {
                // 左側邊緣停留：向上捲動
                performScroll(up = true)
            }
            right -> {
                // 右側邊緣停留：向下捲動
                performScroll(up = false)
            }
        }
    }

    private fun performScroll(up: Boolean) {
        val startY = if (up) screenH * 0.75f else screenH * 0.25f
        val endY = if (up) screenH * 0.25f else screenH * 0.75f
        val midX = screenW / 2f

        val path = Path().apply {
            moveTo(midX, startY)
            lineTo(midX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                cursorView?.post { cursorView?.flashClick() }
            }
        }, null)
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

    private fun performDoubleTap(x: Float, y: Float) {
        performTap(x, y)
        mainHandler.postDelayed({
            performTap(x, y)
        }, 150)
    }

    private fun performLongPress(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 800))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                cursorView?.post { cursorView?.flashClick() }
            }
        }, null)
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
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
        controlView?.let {
            runCatching {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it)
            }
        }
        controlView = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit


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
            // engine.stop() 會阻塞等待 analysis 執行緒排空，丟到背景執行緒避免卡主執行緒
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
        private const val CONTROL_DWELL_MS = 1000L // 面板按鈕注視 1 秒觸發切換
        private const val COOLDOWN_MS = 1200L     // 點擊後的不應期，避免連點
    }
}
