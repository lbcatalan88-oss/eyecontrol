# EyeControl 眼控

用手機前鏡頭追蹤眼球，以視線移動游標、凝視停留點擊，直接控制整支 Android 手機的原型系統。

## 架構

```
前鏡頭 (CameraX)
  → MediaPipe Face Landmarker（478 臉部關鍵點，含虹膜）
  → GazeFeatureExtractor：虹膜相對眼眶位置 + 頭部位置特徵（8 維）
  → GazeModel：9 點校正訓練的嶺回歸，特徵 → 螢幕座標
  → OneEuroFilter：平滑去抖動
  → GazeAccessibilityService：覆蓋層游標 + 凝視停留(1s)觸發 dispatchGesture 點擊
```

| 檔案 | 職責 |
|------|------|
| `FaceTrackerEngine.kt` | 前鏡頭串流 → MediaPipe → GazeSample（校正頁與服務共用） |
| `GazeFeatureExtractor.kt` | 臉部關鍵點 → 視線特徵向量 |
| `GazeModel.kt` | 嶺回歸訓練/預測/存檔（SharedPreferences） |
| `OneEuroFilter.kt` | 游標平滑濾波 |
| `CalibrationActivity.kt` | 全螢幕 9 點校正 |
| `GazeAccessibilityService.kt` | 游標覆蓋層、dwell 點擊、手勢注入 |
| `MainActivity.kt` | 權限/校正/服務三步驟入口 |

## 使用流程

1. 安裝 APK，開啟 App
2. 按「授予相機權限」
3. 按「開始眼球校正」——手機立在面前約 30–40 cm，**頭保持不動**，只用眼睛依序看 9 個點
4. 按「開啟無障礙服務」→ 系統設定中啟用「眼控 EyeControl」
5. 藍色游標隨視線移動，凝視同一處 1 秒即點擊

## 建置

```
java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

需求：JDK 17、Android SDK (API 36)。模型檔 `app/src/main/assets/face_landmarker.task`
來自 https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task

## 已知限制（原型階段）

- 精度約數十像素級，適合點大按鈕；頭部大幅移動後需重新校正
- 目前僅支援「點擊」，尚無捲動/返回/Home 等手勢
- 螢幕方向固定直向（校正座標系綁定校正當下的方向）
- 光線不足時虹膜偵測品質下降

## 隱私

所有影像處理皆在裝置端即時完成：前鏡頭影像僅用於當下的視線推算，**不儲存、不上傳**；
校正資料只有嶺回歸的權重數值（不含任何影像或生物特徵原始資料），以 `MODE_PRIVATE`
存於本機 SharedPreferences。本 App 不連網、不含任何第三方追蹤。

## 授權

MIT License
