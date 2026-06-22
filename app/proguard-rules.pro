# Add project specific ProGuard rules here.

# Keep LinuxDroid core classes
-keep class com.linuxdroid.** { *; }

# Keep terminal emulator classes
-keep class com.linuxdroid.terminal.** { *; }

# Keep command implementations (loaded via reflection)
-keep class com.linuxdroid.commands.** { *; }
-keepclassmembers class com.linuxdroid.commands.** {
    public <init>();
    public static ** getInstance();
}

# Kotlin metadata
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontwarn kotlinx.coroutines.**
