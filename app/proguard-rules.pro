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

# sherpa-onnx 的 native 库按类名与方法名回查 Java 侧，AAR 自带的 proguard.txt 是空文件。
# 只保留声明了 native 方法的类名与方法名；其余成员照常混淆与裁剪，避免放大包体。
# 该依赖只进 full 变体，nosherpa 包里没有这些类。
-keepclasseswithmembers class com.k2fsa.sherpa.onnx.** {
    native <methods>;
}
