# kotlinx.serialization：保留生成的 serializer
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# smbj 用到反射构造部分内部类
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**

# smbj 的事件总线（mbassy）带一个可选的 EL 表达式过滤器，依赖 javax.el。
# Android 上没有 javax.el，这条依赖链也用不到（我们不发 EL 表达式事件），
# 不忽略的话 R8 会直接以 "Missing class javax.el.*" 失败。
-dontwarn net.engio.mbassy.**
-dontwarn javax.el.**

# kotlinx.serialization 的枚举序列化会反射查 values()/name()
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
