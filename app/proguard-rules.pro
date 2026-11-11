# Release builds run R8 in full mode: shrink, optimize, obfuscate.
# Keep line numbers for readable crash reports; identifiers stay obfuscated.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-allowaccessmodification

# Optional references pulled in by Tink/Protobuf.
-dontwarn com.google.protobuf.**
-dontwarn javax.annotation.**

# --- Shizuku -------------------------------------------------------------
# Shizuku talks to the server over AIDL and loads the provider via reflection.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# --- LibADB --------------------------------------------------------------
# Certificate/TLS classes are resolved reflectively by Conscrypt.
-keep class io.github.muntashirakon.adb.** { *; }
-dontwarn io.github.muntashirakon.adb.**

# --- Conscrypt + sun-security -------------------------------------------
# Both ship JNI bindings and look classes up by name at runtime.
-keep class org.conscrypt.** { *; }
-keep class android.sun.** { *; }
-keep class com.android.org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-dontwarn android.sun.**
-dontwarn com.android.org.conscrypt.**

# ADB pairing certificates are serialized, so keep the security providers.
-keep class * extends java.security.Provider
