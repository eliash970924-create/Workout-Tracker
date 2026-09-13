# kotlinx.serialization keeps generated serializers reachable via companions.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.workouttracker.** {
    *** Companion;
}
-keepclasseswithmembers class com.workouttracker.** {
    kotlinx.serialization.KSerializer serializer(...);
}
