# Xposed 入口类与所有 Hook 相关类必须保留（虽然 release 未开启混淆，此处仍显式声明）
-keep class com.liquidglass.mipay.LiquidGlassModule { *; }
-keep class com.liquidglass.mipay.** { *; }

# Xposed API 由框架提供
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }
