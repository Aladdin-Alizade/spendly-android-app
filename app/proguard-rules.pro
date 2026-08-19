# kotlinx.serialization keeps its generated serializers on the classes it
# annotates; without this the release build loses them and stored data fails
# to read back.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class az.spendly.domain.** {
    *** Companion;
}
-keepclasseswithmembers class az.spendly.domain.** {
    kotlinx.serialization.KSerializer serializer(...);
}
