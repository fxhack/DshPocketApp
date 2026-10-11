# WebView 应用，未启用混淆；保留 WebView 与 JS 接口相关类。
-keep class android.webkit.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
