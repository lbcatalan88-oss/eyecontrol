package com.example.eyecontrol

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 視線 → 螢幕座標的線性回歸模型（兩組權重：x 與 y 各一）。
 * 校正時以嶺回歸（ridge regression）解正規方程訓練；特徵僅 8 維，
 * 9 點 × 數十樣本的資料量下解 8×8 線性系統即可，不需要任何 ML 框架。
 */
class GazeModel(private val wx: DoubleArray, private val wy: DoubleArray) {

    /** 預測螢幕座標（像素）。 */
    fun predict(f: DoubleArray): Pair<Double, Double> {
        var px = 0.0
        var py = 0.0
        for (i in f.indices) {
            px += wx[i] * f[i]
            py += wy[i] * f[i]
        }
        return px to py
    }

    fun save(context: Context) {
        val json = JSONObject().apply {
            put("wx", JSONArray(wx.toList()))
            put("wy", JSONArray(wy.toList()))
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
                GazeModel(arr("wx"), arr("wy"))
            }.getOrNull()
        }

        fun exists(context: Context) = prefs(context).contains(KEY)

        /**
         * 嶺回歸訓練：w = (XᵀX + λI)⁻¹ Xᵀy，以高斯消去法解線性系統。
         * @param features 每筆樣本的特徵向量（維度 = GazeFeatureExtractor.DIM）
         * @param targets  對應的螢幕座標（像素）
         */
        fun train(
            features: List<DoubleArray>,
            targets: List<Pair<Float, Float>>,
            lambda: Double = 1e-4,
        ): GazeModel {
            require(features.isNotEmpty() && features.size == targets.size)
            val d = features[0].size

            // XᵀX + λI 與 Xᵀy（x、y 兩個目標共用同一個左側矩陣）
            val ata = Array(d) { DoubleArray(d) }
            val atx = DoubleArray(d)
            val aty = DoubleArray(d)
            for (n in features.indices) {
                val f = features[n]
                val (tx, ty) = targets[n]
                for (i in 0 until d) {
                    for (j in 0 until d) ata[i][j] += f[i] * f[j]
                    atx[i] += f[i] * tx
                    aty[i] += f[i] * ty
                }
            }
            for (i in 0 until d) ata[i][i] += lambda

            return GazeModel(solve(ata, atx), solve(ata, aty))
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
