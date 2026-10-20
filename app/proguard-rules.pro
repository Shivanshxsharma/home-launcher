# Little reflection in the app; the defaults from proguard-android-optimize.txt cover the rest.

# The trailer page calls back into TrailerActivity.Bridge by method name from JavaScript.
-keepclassmembers class com.shivansh.mibox.TrailerActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
