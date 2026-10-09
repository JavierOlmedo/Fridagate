# R8 rules for Fridagate release builds.
# R8 shrinks, optimizes and renames code. Anything read by reflection must be kept here.

# Gson builds these classes by reflection when it parses the GitHub releases API
# (FridaUtils). Keep them, their fields and the generic signature of List<GithubAsset>,
# otherwise the Frida version list comes back empty in release builds.
-keepattributes Signature, RuntimeVisibleAnnotations, AnnotationDefault
-keep class com.hackpuntes.fridagate.utils.FridaUtils$GithubRelease { *; }
-keep class com.hackpuntes.fridagate.utils.FridaUtils$GithubAsset { *; }

# Keep file names and line numbers so crash stack traces stay readable
# (they can be decoded with the mapping.txt of each build).
-keepattributes SourceFile, LineNumberTable
