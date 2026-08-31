# kotlinx.serialization garde les serializers generes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.mkdev.portainerremote.** {
    *** Companion;
}
-keepclasseswithmembers class dev.mkdev.portainerremote.** {
    kotlinx.serialization.KSerializer serializer(...);
}
