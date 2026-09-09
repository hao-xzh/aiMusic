# Android 组件由 Manifest 生成的规则保留；Media3、Compose、Coil 和协程
# 使用各依赖附带的 consumer rules，不再整包禁止裁剪和优化。

# Rust 导出函数按类名和 native 方法名绑定，只保留实际 JNI 边界。
-keep,allowoptimization class app.pipo.nativeapp.data.JsonRustPipoBridge {
    native <methods>;
}
-keepclasseswithmembernames class * {
    native <methods>;
}

# 播放通知通过 Class.forName 创建 Activity Intent。
-keepnames class app.pipo.nativeapp.MainActivity

# 保留诊断堆栈和运行时注解所需信息，业务方法仍可优化。
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable
