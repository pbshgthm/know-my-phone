# Keep data classes used with Gson
-keepclassmembers class com.knowmyphone.app.model.** { *; }
-keepclassmembers class com.knowmyphone.app.network.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
