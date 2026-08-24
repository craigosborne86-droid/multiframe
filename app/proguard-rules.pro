# The merge pipeline is plain Kotlin with no reflection, so the defaults are
# sufficient. Rules are listed explicitly rather than left implicit so a future
# change that does use reflection has an obvious place to declare it.

# Keep line numbers so release crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# JNI resolves native methods by their fully qualified Java name, so renaming
# either of these classes would break the link at runtime rather than at build
# time. The default Android config keeps classes with native methods, but this
# is the one thing in the app where a rename is silently fatal, so it is stated
# rather than inherited.
-keepclasseswithmembernames,includedescriptorclasses class dev.multiframe.camera.pipeline.NativeMerge {
    native <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses class dev.multiframe.camera.pipeline.RawRing {
    native <methods>;
}
