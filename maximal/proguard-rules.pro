# The trailer page calls back into TrailerActivity.Bridge by method name from JavaScript.
-keepclassmembers class com.shivansh.mibox.maximal.TrailerActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
