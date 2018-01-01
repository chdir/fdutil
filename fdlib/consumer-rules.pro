# Keep native methods usable by denying obfuscation for their classes.
# This rule still allows their removal during shrinking phase.
# We aren't using includedescriptorclasses here, because return types
# of native methods don't have to be kept for them to work.
# Optimizations are permitted to let Proguard relax access modifiers
# of native methods during repackaging
-keepclasseswithmembers,allowshrinking,allowoptimization class net.sf.xfd.** {
    native <methods>;
}

# Prevent removal of callback methods only if their classes are not removed
# This ensures, that their full signatures are preserved via includedescriptorclasses
# Two separate blocks and no "*" (asterisc) because of how keepclasseswithmembers works
-keep class android.support.annotation.Keep {}
-keepclasseswithmembers,allowshrinking,allowoptimization,includedescriptorclasses class net.sf.xfd.** {
    @android.support.annotation.Keep <methods>;
}
-keepclasseswithmembers,allowshrinking,allowoptimization,includedescriptorclasses class net.sf.xfd.** {
    @android.support.annotation.Keep <fields>;
}
-keepclassmembers,allowoptimization,includedescriptorclasses class net.sf.xfd.** {
    @android.support.annotation.Keep <methods>;
    @android.support.annotation.Keep <fields>;
}

# always keep annotated exceptions (they can be thrown from native code under some circumstances)
-keep,includedescriptorclasses @android.support.annotation.Keep class net.sf.xfd.** extends java.lang.Throwable {
    *;
}

# if entire class is annotated, preserve it and all it's contents from obfuscation
-keep,allowshrinking,includedescriptorclasses @android.support.annotation.Keep public class net.sf.xfd.** {
    *;
}

-dontwarn **$$Lambda$*
-dontwarn javax.lang.model.**
-dontwarn javax.tools.**
-dontwarn javax.annotation.**
-dontwarn com.squareup.javapoet.**
-dontwarn com.google.auto.**
-dontwarn com.google.common.**
-dontwarn sun.misc.Unsafe
-dontwarn sun.nio.ch.DirectBuffer
-dontwarn org.jetbrains.annotations.**