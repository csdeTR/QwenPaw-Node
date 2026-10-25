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

# OkHttp / Okio 的可选依赖
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tink（EncryptedSharedPreferences 的传递依赖）只在编译期引用 errorprone 注解
-dontwarn com.google.errorprone.annotations.**
