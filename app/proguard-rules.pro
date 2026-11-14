# R8 / ProGuard keep rules for tucaVR release builds

# C++ OpenXR / JNI callbacks lookup @JvmStatic methods on VRActivity by name and signature,
# and native methods register against com.tucavr.VRActivity and com.tucavr.download.DownloadBridge.
-keep class com.tucavr.VRActivity {
    native <methods>;
    public static <methods>;
}
-keep class com.tucavr.VRActivity$Companion {
    public <methods>;
}
-keep class com.tucavr.download.DownloadBridge {
    native <methods>;
}

# Room instantiates AppDatabase_Impl reflectively via Room.databaseBuilder.
-keep class * extends androidx.room.RoomDatabase
-dontwarn org.mozilla.gecko.**
# SnakeYAML references desktop java.beans APIs absent on Android runtime.
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
