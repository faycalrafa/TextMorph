# Proguard rules for TextMorph
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.* *;
}
-dontwarn io.ktor.**
-dontwarn kotlinx.serialization.**
