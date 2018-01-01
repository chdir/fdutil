-dontwarn android.support.v4.app.ActivityCompatApi21
-dontwarn okio.**
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.ParametersAreNonnullByDefault
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn **$$Lambda$*

-keepattributes Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

-keep,allowoptimization public class * implements butterknife.ViewBinder {
    public <init>();
}
-keepclasseswithmembernames,allowoptimization,includedescriptorclasses class * {
    @butterknife.* <methods>;
}
-keepclasseswithmembernames,allowoptimization,includedescriptorclasses class * {
    @butterknife.* <fields>;
}

-optimizationpasses 6

-repackageclasses xfd
-allowaccessmodification
-useuniqueclassmembernames
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers

-dontwarn butterknife.internal.ButterKnifeProcessor
-dontwarn java.nio.file.**
-dontwarn com.google.j2objc.**