package com.example.eyecontrol

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 視線 → 螢幕座標的線性回歸模型（兩組權重：x 與 y 各一）。
 * 校正時以嶺回歸（ridge regression）解正規方程訓練；特徵僅 8 維，
 * 9 點 × 數十樣本的資料量下解 8×8 線性系統即可，不需要任何 ML 框架。
 */
class GazeModel(
    private val wx: DoubleArray,
    private val wy: DoubleArray,
    var basePitch: Double = 0.0,
    var baseYaw: Double = 0.0,
    var kx: Double = 1.6,
    var ky: Double = 1.3
) {

    /** 預測螢幕座標（像素），並加上幾何頭部晃動補償。 */
    fun predict(f: DoubleArray, screenW: Int = 1080, screenH: Int = 2400): Pair<Double, Double> {
        var px = 0.0
        var py = 0.0
        for (i in f.indices) {
            px += wx[i] * f[i]
            py += wy[i] * f[i]
        }

        // 頭部姿態補償 (Head pose compensation)
        val curPitch = f[7]
        val curYaw = f[8]
        val deltaPitch = curPitch - basePitch
        val deltaYaw = curYaw - baseYaw

        // 幾何補償：使用自適應的 kx 與 ky
        val compensatedX = px + deltaYaw * screenW * kx
        val compensatedY = py - deltaPitch * screenH * ky

        return compensatedX to compensatedY
    }

    fun save(context: Context) {
        val json = JSONObject().apply {
            put("wx", JSONArray(wx.toList()))
            put("wy", JSONArray(wy.toList()))
            put("basePitch", basePitch)
            put("baseYaw", baseYaw)
            put("kx", kx)
            put("ky", ky)
        }
        prefs(context).edit().putString(KEY, json.toString()).apply()
    }

    companion object {
        private const val PREFS = "gaze_model"
        private const val KEY = "model_json"

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun load(context: Context): GazeModel? {
            val raw = prefs(context).getString(KEY, null) ?: return null
            return runCatching {
                val json = JSONObject(raw)
                fun arr(k: String): DoubleArray {
                    val a = json.getJSONArray(k)
                    return DoubleArray(a.length()) { a.getDouble(it) }
                }
                val wxArr = arr("wx")
                val wyArr = arr("wy")
                val basePitchVal = json.optDouble("basePitch", 0.0)
                val baseYawVal = json.optDouble("baseYaw", 0.0)
                val kxVal = json.optDouble("kx", 1.6)
                val kyVal = json.optDouble("ky", 1.3)
                val m = GazeModel(wxArr, wyArr, basePitchVal, baseYawVal, kxVal, kyVal)
                // 特徵定義改版後舊模型維度不符，視為未校正
                if (m.wx.size != GazeFeatureExtractor.DIM) null else m
            }.getOrNull()
        }

        fun exists(context: Context) = load(context) != null

        /**
         * 嶺回歸訓練：w = (XᵀX + λI)⁻¹ Xᵀy，以高斯消去法解線性系統。
         * @param features 每筆樣本的特徵向量（維度 = GazeFeatureExtractor.DIM）
         * @param targets  對應的螢幕座標（像素）
         */
        fun train(
            features: List<DoubleArray>,
            targets: List<Pair<Float, Float>>,
            lambda: Double = 5e-4,
        ): GazeModel {
            require(features.isNotEmpty() && features.size == targets.size)
            val d = features[0].size // 22

            var pitchSum = 0.0
            var yawSum = 0.0
            for (n in features.indices) {
                pitchSum += features[n][7]
                yawSum += features[n][8]
            }
            val basePitch = pitchSum / features.size
            val baseYaw = yawSum / features.size

            // 從目標座標估算螢幕維度 (CalibrationActivity 使用 8% 邊距，最大座標約在 92%)
            val maxX = targets.maxOfOrNull { it.first } ?: 1080f
            val maxY = targets.maxOfOrNull { it.second } ?: 2400f
            val screenW = if (maxX > 0) (maxX / 0.92f).toDouble() else 1080.0
            val screenH = if (maxY > 0) (maxY / 0.92f).toDouble() else 2400.0

            // 擴展特徵向量維度，將姿勢補償項放入特徵矩陣中，利用嶺回歸同時優化 w 與 k
            val extD = d + 1

            // 1. 訓練 X 軸 (同時求得 wx 與 kx)
            val ataX = Array(extD) { DoubleArray(extD) }
            val atx = DoubleArray(extD)
            for (n in features.indices) {
                val f = features[n]
                val (tx, _) = targets[n]
                val curYaw = f[8]
                val deltaYaw = curYaw - baseYaw
                val compTermX = deltaYaw * screenW

                val fExt = DoubleArray(extD)
                System.arraycopy(f, 0, fExt, 0, d)
                fExt[d] = compTermX

                for (i in 0 until extD) {
                    for (j in 0 until extD) ataX[i][j] += fExt[i] * fExt[j]
                    atx[i] += fExt[i] * tx
                }
            }
            for (i in 0 until extD) ataX[i][i] += lambda
            val solX = solve(ataX, atx)
            val wx = solX.copyOfRange(0, d)
            val kx = solX[d].coerceIn(0.5, 3.0) // 限制補償範圍以確保穩定

            // 2. 訓練 Y 軸 (同時求得 wy 與 ky)
            val ataY = Array(extD) { DoubleArray(extD) }
            val aty = DoubleArray(extD)
            for (n in features.indices) {
                val f = features[n]
                val (_, ty) = targets[n]
                val curPitch = f[7]
                val deltaPitch = curPitch - basePitch
                val compTermY = -deltaPitch * screenH

                val fExt = DoubleArray(extD)
                System.arraycopy(f, 0, fExt, 0, d)
                fExt[d] = compTermY

                for (i in 0 until extD) {
                    for (j in 0 until extD) ataY[i][j] += fExt[i] * fExt[j]
                    aty[i] += fExt[i] * ty
                }
            }
            for (i in 0 until extD) ataY[i][i] += lambda
            val solY = solve(ataY, aty)
            val wy = solY.copyOfRange(0, d)
            val ky = solY[d].coerceIn(0.5, 3.0) // 限制補償範圍以確保穩定

            return GazeModel(wx, wy, basePitch, baseYaw, kx, ky)
        }

        /** 高斯消去法（含部分主元選取）解 A·w = b。A 會被複製，不改動原矩陣。 */
        private fun solve(aIn: Array<DoubleArray>, bIn: DoubleArray): DoubleArray {
            val n = bIn.size
            val a = Array(n) { aIn[it].copyOf() }
            val b = bIn.copyOf()

            for (col in 0 until n) {
                var pivot = col
                for (r in col + 1 until n) {
                    if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[pivot][col])) pivot = r
                }
                if (pivot != col) {
                    val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
                    val tb = b[col]; b[col] = b[pivot]; b[pivot] = tb
                }
                val p = a[col][col]
                if (kotlin.math.abs(p) < 1e-12) continue // 退化行，交給正則項兜底
                for (r in col + 1 until n) {
                    val factor = a[r][col] / p
                    for (c in col until n) a[r][c] -= factor * a[col][c]
                    b[r] -= factor * b[col]
                }
            }
            val w = DoubleArray(n)
            for (row in n - 1 downTo 0) {
                var s = b[row]
                for (c in row + 1 until n) s -= a[row][c] * w[c]
                w[row] = if (kotlin.math.abs(a[row][row]) < 1e-12) 0.0 else s / a[row][row]
            }
            return w
        }
    }
}
