# Keep Room, Kotlinx Serialization, and PDFBox classes
-keepattributes *Annotation*
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-dontwarn org.bouncycastle.**
-dontwarn javax.xml.stream.**
