# Names stay as written: a crash in a player's Logs reads without a mapping file.
-dontobfuscate

# Termux:X11 is reached by name from its native code (JNI) and started through app_process.
-keep class com.termux.x11.** { *; }

# commons-compress's zstd codec needs zstd-jni, which the app leaves out: it opens .tar.xz, .tar.gz and .zip.
-dontwarn com.github.luben.zstd.**
