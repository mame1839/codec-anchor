# Xposed のエントリポイントはリフレクションで読まれるため保持する
-keep class io.github.mame1839.codecanchor.xposed.** { *; }
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit { *; }
