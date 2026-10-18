# Ported from Files (YAZ/Files at HEAD, app/proguard-rules.pro), itself from Calculator (github.com/213YaZ786/Calculator, app/proguard-rules.pro)
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.yaz.store.** {
    *** Companion;
}
-keepclasseswithmembers class com.yaz.store.** {
    kotlinx.serialization.KSerializer serializer(...);
}
