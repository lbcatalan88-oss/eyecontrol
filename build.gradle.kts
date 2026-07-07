// 頂層 build 檔：只宣告各模組共用的 plugin 版本，實際套用在各模組。
plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}
