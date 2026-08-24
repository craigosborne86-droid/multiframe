# The merge pipeline is plain Kotlin with no reflection, so the defaults are
# sufficient. Rules are listed explicitly rather than left implicit so a future
# change that does use reflection has an obvious place to declare it.

# Keep line numbers so release crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
