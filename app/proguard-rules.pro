# ProGuard rules for NAS Music TV

# Keep data classes
-keep class com.nasmusic.tv.data.model.** { *; }
-keep class com.nasmusic.tv.data.prefs.** { *; }
-keep class com.nasmusic.tv.data.stats.** { *; }
-keep class com.nasmusic.tv.backend.** { *; }

# 百度网盘 DTO 类（显式 keep，防御 Gson 类型擦除/R8 收缩——v2.5.1 曾因此崩溃；
# 与上方 backend.** 宽规则冗余但明确，新增序列化模型时勿删）
-keep class com.nasmusic.tv.backend.network.baidu.** { *; }

# Gson
-keep class com.google.gson.** { *; }

# ⛔ 泛型签名（v2.39.2 修复）：`object : TypeToken<Map<String, X>>() {}.type` 依赖
# class 文件里的 Signature 属性。R8 默认会剥掉它 ⇒ release 包运行时报
# `IllegalStateException: TypeToken must be created with a type argument`。
# 影响面：LyricsPersistentCache / CoverUrlPersistentCache / MvPersistentCache /
# BaiduFileIndexCache / AppPreferences 的历史与统计反序列化等**全部 TypeToken 调用点**。
# 该问题只在 release 出现，debug 与单测都测不到 —— 由「日志」页在真机上抓到。
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod
# TypeToken 的匿名子类必须连签名一起保留（只保留 Gson 自身类不够）
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ExoPlayer
-keep class androidx.media3.** { *; }

# ZXing (二维码生成)
-keep class com.google.zxing.** { *; }

# NanoHTTPD (本地 HTTP server，实际 Java 包为 fi.iki.elonen)
-keep class fi.iki.elonen.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ONNX Runtime (Spleeter ONNX 人声分离)
-keep class ai.onnxruntime.** { *; }
-keep class com.microsoft.onnxruntime.** { *; }

# Strip debug logs in release builds (ProGuard removes the entire Log.d/v call,
# including string computation — more efficient than runtime if(BuildConfig.DEBUG) checks)
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# jaudiotagger — R8 下反射访问字段需要保留
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**

# 音乐可视化（visualizer 包）——20 套渲染器经 VisualizerRendererFactory 按枚举名创建，
# 且 AudioFrame/CoverPalette 参与跨层引用；枚举名会持久化到 DataStore（visualizer_theme /
# visualizer_quality）。R8 收缩枚举常量或重命名后会导致主题解析失败 / 渲染器创建失败。
# 与 v2.5.1 的 Gson 类型擦除崩溃同类，新增效果时勿删。
-keep class com.nasmusic.tv.visualizer.** { *; }
-keepclassmembers enum com.nasmusic.tv.data.model.VisualizerTheme { *; }
-keepclassmembers enum com.nasmusic.tv.data.model.VisualQuality { *; }
-keepclassmembers enum com.nasmusic.tv.data.model.VisualizerTheme$Tier { *; }
# Android 上不存在 java.awt / javax.imageio，忽略引用
-dontwarn java.awt.**
-dontwarn javax.imageio.ImageIO
-dontwarn javax.imageio.stream.ImageInputStream
