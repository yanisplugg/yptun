# --- App entry points referenced by the system / by name ---
-keep class org.olcbox.app.vpn.service.OlcboxVpnService { *; }

# --- gomobile-bound native cores (accessed via JNI by class/method name) ---
# Renaming or stripping these breaks the Go<->Kotlin bridge at runtime.
-keep class go.** { *; }
-keep class seq.** { *; }
-keep class libbox.** { *; }
-keep class xraybridge.** { *; }
-keep class mobile.** { *; }

# --- kotlinx.serialization ---
# Standard R8/ProGuard rules so @Serializable models keep their generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Keep the Companion of @Serializable classes.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
# Keep serializer() on those companions.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep the generated $serializer classes.
-if @kotlinx.serialization.Serializable class **
-keep class <1>$$serializer { *; }

# Keep our serializable data models outright (they are (de)serialized to/from disk & network).
-keep @kotlinx.serialization.Serializable class org.olcbox.app.** { *; }
-keep class org.olcbox.app.**$$serializer { *; }
