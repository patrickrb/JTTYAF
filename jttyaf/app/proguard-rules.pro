# ProGuard / R8 rules for the release build.
#
# Release is built with minifyEnabled true (shrink + obfuscate). The default
# proguard-android-optimize.txt already covers the common cases we rely on:
#   * classes that declare `native` methods keep their name + the native method
#     names (so the libft8af.so JNI symbol names Java_com_k1af_ft8af_* still
#     resolve) — covers FT8SignalListener, GenerateFT8, FT8Package, FT8Resample,
#     SpectrumView, SpectrumFragment, UsbAudioNative, ReBuildSignal.
#   * enum values()/valueOf() and Parcelable CREATOR fields.
# Everything below is the project-specific surface R8 cannot infer on its own:
# names the native layer (or reflection) looks up by string.

# Keep source/line metadata so the uploaded mapping.txt can deobfuscate crash
# stack traces back to real file + line numbers in Play Console, then hide the
# original source file name (it becomes "SourceFile" in the obfuscated build).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- JNI: fields written by name via GetObjectClass(frame)+GetFieldID(...) ---
# jttyaf_glue/jtty_jni.cpp fills a JttyCodec.DecodedFrame field by field when a
# frame is popped from the native receiver. The field names are baked into the
# C++; obfuscating them makes every decode silently fail. Keep the class name
# and all fields. NativeJttyCodec's native methods are kept by the default
# rules (classes declaring native methods keep their names).
-keepclassmembers class radio.ks3ckc.jttyaf.jtty.JttyCodec$DecodedFrame {
    <fields>;
}
-keep class radio.ks3ckc.jttyaf.jtty.NativeJttyCodec { *; }

# --- JNI: callback methods invoked by name via GetMethodID(...) ---
# usb_audio_capture.cpp calls back into the AudioInputCallback by method name +
# signature. Keep the interface methods so implementers keep the same names.
-keepclassmembers interface com.k1af.ft8af.wave.UsbAudioNative$AudioInputCallback {
    void onAudioData(float[], int);
    void onCaptureStopped(int);
}

# --- Reflection: USB serial driver discovery ---
# UsbSerialProber instantiates each driver via getConstructor(UsbDevice.class)
# .newInstance(...), and ProbeTable invokes the static getSupportedDevices()
# via getMethod("getSupportedDevices"). Both are reached only reflectively, so
# R8 would otherwise drop them as unused.
-keepclassmembers class * implements com.k1af.ft8af.serialport.UsbSerialDriver {
    public <init>(android.hardware.usb.UsbDevice);
    public static java.util.Map getSupportedDevices();
}

# --- Plain JARs without consumer rules: silence missing optional references ---
# nanohttpd / commons-net are used directly (no reflection on our types) but
# reference optional APIs R8 can't resolve; -dontwarn keeps the build from
# failing on those phantom references.
-dontwarn fi.iki.elonen.**
-dontwarn org.apache.commons.net.**
