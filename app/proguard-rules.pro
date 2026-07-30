# Xposed はエントリクラスを assets/xposed_init に書いた名前で読み込むため、名前は変えない
-dontobfuscate

-keep class io.github.mame1839.codecanchor.xposed.** { *; }
-keep class io.github.mame1839.codecanchor.core.** { *; }
-keep class io.github.mame1839.codecanchor.bridge.** { *; }
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit { *; }

# Xposed API は実行時に LSPosed / Vector が提供する
-dontwarn de.robv.android.xposed.**
