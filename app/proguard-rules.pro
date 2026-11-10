# Release builds run R8 in full mode: shrink, optimize, obfuscate.
# Keep line numbers for readable crash reports; identifiers stay obfuscated.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-allowaccessmodification

# Optional references pulled in by Tink/Protobuf.
-dontwarn com.google.protobuf.**
-dontwarn javax.annotation.**
