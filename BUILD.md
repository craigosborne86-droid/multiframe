# Build notes

## Toolchain (verified 2026-08-24)

| Component | Version | Notes |
|---|---|---|
| JDK | Temurin 21.0.12.1 LTS | `~/Library/Java/JavaVirtualMachines/` |
| Gradle | 9.5.0 | via wrapper |
| AGP | 9.3.2 | Kotlin support is **built-in**; do not apply `org.jetbrains.kotlin.android` |
| Kotlin | 2.4.10 | compose plugin applied separately |
| compileSdk | 37 | AndroidX 1.19.x requires 37+ |
| targetSdk | 36 | Android 16 — Play requirement, re-confirm at Phase 5 |
| minSdk | 33 | AGSL/RenderEffect floor for GPU tone mapping without RenderScript |
| CameraX | 1.6.1 | incl. `camera-compose` (stable) |

## exFAT gotcha

The project volume is exFAT, which writes AppleDouble `._*` sidecars whenever
extended attributes are set. AGP fails on these (`'._drawable' is not a
directory`), so `settings.gradle.kts` redirects all build output to
`~/Library/Caches/MultiframeBuild/` on the internal APFS volume.

Sources still accumulate `._*` files. Clean before building:

    find . -name '._*' -delete

## Build

    export JAVA_HOME="$HOME/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home"
    ./gradlew :app:assembleDebug

APK lands in `~/Library/Caches/MultiframeBuild/app/outputs/apk/debug/`.
