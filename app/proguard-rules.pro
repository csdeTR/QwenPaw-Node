# QwenPaw Node 混淆规则

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.qwenpaw.node.**$$serializer { *; }
-keepclassmembers class io.qwenpaw.node.** {
    *** Companion;
}
-keepclasseswithmembers class io.qwenpaw.node.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room 生成的实现类通过反射加载
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# SAF 目录树：DocumentFile 按文档 Uri 反射取实现类
-keep class androidx.documentfile.provider.** { *; }

# OkHttp / Okio 的可选依赖
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tink（EncryptedSharedPreferences 的传递依赖）只在编译期引用 errorprone 注解
-dontwarn com.google.errorprone.annotations.**

# JSch（SSH 终端）通过 JCE 与反射查找算法、密钥格式，保留类名与方法名。
# 它可能引用可选的 BouncyCastle；本工程不引入 BC，缺类只需 dontwarn。
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**

# sherpa-onnx 的 native 库按类名与方法名回查 Java 侧，AAR 自带的 proguard.txt 是空文件。
# 只保留声明了 native 方法的类名与方法名；其余成员照常混淆与裁剪，避免放大包体。
# 该依赖只进 full 变体，nosherpa 包里没有这些类。
-keepclasseswithmembers class com.k2fsa.sherpa.onnx.** {
    native <methods>;
}

# termlib 的 JNI 入口；AAR 自带 consumer rules，这里再显式保留一层。
-keep class org.connectbot.terminal.TerminalNative { *; }
-dontwarn org.slf4j.**

# ZXing 核心库：解码器按符号类型实例化各 Reader，类名出现在字符串里，
# 必须保留构造与入口方法，否则 release 包扫码永远解不出来。
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# CameraX 通过运行时反射查找相机实现（camera-camera2 的 Provider 由
# ServiceLoader 风格的名称匹配装载），保留实现类与 Provider 入口。
-keep class androidx.camera.camera2.** { *; }
-keep class androidx.camera.core.** { *; }
-keep interface androidx.camera.core.CameraProvider { *; }
-dontwarn androidx.camera.**

# ML Kit 的组件发现按原始类名查找接口与实现；只保留 barcode 包不够，
# common.sdkinternal.ExecutorSelector 被 R8 改名后会在启动时抛
# UnsatisfiedDependencyException。common、vision 与 GMS 内部实现一起保留。
-keep class com.google.mlkit.** { *; }
-keep interface com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode_bundled.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_**
