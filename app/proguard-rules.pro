-keep class com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }

# sherpa-onnx JNI: config data classes are accessed reflectively from native code
-keep class com.k2fsa.sherpa.onnx.** { *; }

# B30: EventBus subscriber methods are looked up via reflection; keep them
# (narrow: annotated members only, not the whole app).
-keepclassmembers class * {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }

# B30: kotlinx.serialization + JetPref DataStore serializers used by the app
# (AppPrefs, ConfigBackup, InstalledModelReference/ModelType, Key and the
# *ListSerializer/*MapSerializer PreferenceSerializer classes). Keep the
# generated $$serializer companions and @Serializable classes' fields.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class kotlinx.serialization.** { *; }
-keep class com.elishaazaria.sayboard.data.InstalledModelReference { *; }
-keep class com.elishaazaria.sayboard.data.ModelType { *; }
-keep class com.elishaazaria.sayboard.backup.ConfigBackup { *; }
-keep class com.elishaazaria.sayboard.utils.Key { *; }
-keep class com.elishaazaria.sayboard.utils.KeysListSerializer { *; }
-keep class com.elishaazaria.sayboard.utils.ModelListSerializer { *; }
-keep class com.elishaazaria.sayboard.utils.StringMapSerializer { *; }
-keep class **$$serializer { *; }
-keepclasseswithmembernames class * {
    kotlinx.serialization.KSerializer serializer(...);
}

-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window