# The pipeline is plain Kotlin with no reflection, so the defaults are close
# to sufficient. Every rule here is stated rather than inherited, because the
# one class of failure R8 can introduce in this app -- a native method or its
# class renamed out from under the JNI link -- is silent at build time and
# fatal at first use.

# Keep line numbers so release crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- The JNI boundary ---------------------------------------------------------
#
# JNI resolves a native method by the fully qualified name of its class and
# the method's own name (Java_dev_multiframe_camera_pipeline_RawRing_nPush),
# so neither may be renamed, and a class whose native methods are all "unused"
# to the shrinker must still be kept whole for the link to find it. Nine
# classes carry `external fun`s, one per native subsystem:
#
#   RawRing        RingBuffer.cpp   the ZSL raw ring
#   NativeMerge    bayer_merge.cpp  the merge, the CFA develop, the tone bench
#   Aligner        Align.cpp        the tile search
#   NativeSubPixel SubPixel.cpp     the 2x canvas and the linear develop
#   MosaicCanvas   Mosaic.cpp       the mosaic canvas, seam and refinement
#   NativeOptics   Optics.cpp       undistortion and aberration
#   NativeRotate   Rotate.cpp       the quarter-turn transpose
#   NativeJpeg     Jpeg.cpp         libjpeg-turbo
#   GpuCrossing    GpuCrossing.cpp  the GPU transfer measurement
#
# The rule is by member, not by class name, so adding a tenth class means
# adding a line here -- and `tools/verify-native-bridge.sh` reads the release DEX
# and the .so's exported symbols to check they still agree.
-keepclasseswithmembernames,includedescriptorclasses class dev.multiframe.camera.pipeline.RawRing {
    native <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses class dev.multiframe.camera.pipeline.NativeMerge {
    native <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses class dev.multiframe.camera.pipeline.Aligner {
    native <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses class dev.multiframe.camera.pipeline.NativeSubPixel {
    native <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses class dev.multiframe.camera.pipeline.MosaicCanvas {
    native <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses class dev.multiframe.camera.pipeline.NativeOptics {
    native <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses class dev.multiframe.camera.pipeline.NativeRotate {
    native <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses class dev.multiframe.camera.pipeline.NativeJpeg {
    native <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses class dev.multiframe.camera.pipeline.GpuCrossing {
    native <methods>;
}

# The native side never calls back up (no FindClass, GetMethodID or field
# lookups in app/src/main/cpp), so nothing else needs its name.

# --- Compose and coroutines ---------------------------------------------------
#
# Both ship consumer rules of their own; the Compose compiler's state holders
# (`MutableState`, `SnapshotState*`) and the runtime's `Composer` are kept by
# those. Nothing in this app reaches a composable or a state holder by
# reflection, so nothing further is kept here. The release build is checked
# to compose and run on the device rather than assumed.

# --- Diagnostics --------------------------------------------------------------
#
# Every ProGuard-style warning is an error: a warning about a missing class
# is how a stripped bridge announces itself before the first crash.
-dontwarn kotlinx.coroutines.debug.**
