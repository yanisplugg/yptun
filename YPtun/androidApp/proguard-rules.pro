# --- App entry points referenced by the system / by name ---
-keep class org.olcbox.app.vpn.service.OlcboxVpnService { *; }

# --- gomobile-bound native cores (accessed via JNI by class/method name) ---
# Renaming or stripping these breaks the Go<->Kotlin bridge at runtime.
-keep class go.** { *; }
-keep class seq.** { *; }
-keep class libbox.** { *; }
-keep class xraybridge.** { *; }
-keep class mobile.** { *; }

# --- Trust Tunnel (AdGuard) client AAR — JNI bridge (native <-> Kotlin by class/method name) ---
# libtrusttunnel_android.so calls back into VpnClient/DeepLink/VpnClientListener by name (native
# methods + the listener callbacks), so renaming/stripping them breaks the bridge at runtime.
-keep class com.adguard.trusttunnel.** { *; }
# The AAR bundles a VpnServiceConfig.parseToml() that references ktoml — a transitive dependency we
# don't pull in (we consume the AAR as a local file and never call parseToml; TOML is parsed natively
# inside the client). Silence R8's missing-class error for that unused code path.
-dontwarn com.akuleshov7.ktoml.**

# --- JSch (mwiede fork) — VPS SSH installer (WDTT / DNSTT auto-install) ---
# JSch instantiates its cipher/kex/MAC/random implementations by fully-qualified class name pulled
# from an internal config map (reflection via Class.forName). R8 sees no static references to those
# classes and strips them, so connecting throws ClassNotFoundException (e.g.
# com.jcraft.jsch.jce.Random). Keep the whole package (and its optional agentproxy/jgss helpers).
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

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
