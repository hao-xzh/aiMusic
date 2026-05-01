# ===== Pipo Release ProGuard / R8 规则 =====
# 目标：体积最小的同时不破坏 Compose / Coil / Media3 / Kotlin 协程 / JNI bridge

# Media3 / ExoPlayer —— UnstableApi 注解 + 内部反射
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Compose 运行时
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.tooling.** { *; }

# Kotlin 协程 / Flow
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class kotlin.coroutines.Continuation { *; }
-keepclassmembers class kotlin.coroutines.jvm.internal.SuspendLambda {
    private final java.lang.Object L$0;
}

# JNI Native Bridge —— invokeNative 是 native 方法，必须保留签名
-keep class app.pipo.nativeapp.data.JsonRustPipoBridge { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Coil —— 反射加载 Decoder / Fetcher
-keep class coil.** { *; }
-keep interface coil.** { *; }
-dontwarn coil.**

# OkHttp / Okio —— Coil 依赖
-dontwarn okhttp3.**
-dontwarn okio.**

# ZXing —— QR 码生成
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Pipo 应用自身的 data class
-keep class app.pipo.nativeapp.data.** { *; }
-keep class app.pipo.nativeapp.runtime.** { *; }

# 保留泛型 / 注解 / 内部类签名
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
