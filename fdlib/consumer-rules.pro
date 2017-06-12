# Keep native methods usable by denying obfuscation for their classes.
# This rule still allows their removal during shrinking phase.
# We aren't using includedescriptorclasses here, because return types
# of native methods don't have to be kept for them to work.
# Optimizations are permitted to let Proguard relax access modifiers
# of native methods during repackaging
-keepclasseswithmembers,allowshrinking,allowoptimization class net.sf.xfd.** {
    native <methods>;
}

# denying obfuscation of members, used from native code (usually methods)
-keep,allowshrinking,allowoptimization,includedescriptorclasses class net.sf.xfd.** {
    @android.support.annotation.Keep *;
}

# always keep annotated exceptions (they can be thrown from native code under some circumstances)
-keep,allowoptimization,includedescriptorclasses @android.support.annotation.Keep class net.sf.xfd.** extends java.lang.Throwable {
    *;
}

# if entire class is annotated, preserve it and all it's contents
-keep,allowshrinking,allowoptimization,includedescriptorclasses @android.support.annotation.Keep public class net.sf.xfd.** {
    *;
}