# Result services are referenced from AndroidManifest.xml.
-keep class cn.termux.ubuntumanager.termux.CommandResultService { *; }
-keep class cn.termux.ubuntumanager.operation.OperationForegroundService { *; }
-keepclassmembers class cn.termux.ubuntumanager.ui.TerminalJavascriptBridge {
    @android.webkit.JavascriptInterface <methods>;
}
