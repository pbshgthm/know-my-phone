# Keep data classes used with Gson
-keepclassmembers class com.knowyourphone.app.model.** { *; }
-keepclassmembers class com.knowyourphone.app.network.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
