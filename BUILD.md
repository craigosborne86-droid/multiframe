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

## Tests

    ./gradlew :app:testDebugUnitTest          # 332 tests, no device needed
    ./gradlew :app:connectedDebugAndroidTest  # 95 tests, needs a device or emulator

## Emulator, for when no phone is attached

An arm64 AVD runs this app's own `arm64-v8a` build natively on Apple Silicon --
no translation -- and boots unlocked, which is the condition the Compose tests
need and a locked development phone never gives them.

The internal disk had 6.1 GB free; the API 36 image is 4.3 GB installed and
needs about 5.4 GB more while it unpacks. Both it and the AVD therefore live on
the external drive, reached through two symlinks so the SDK layout is unchanged:

    ~/Library/Android/sdk/system-images -> /Volumes/Plex Files/AndroidEmulator/system-images
    ~/Library/Android/sdk/.temp         -> /Volumes/Plex Files/AndroidEmulator/temp

The `.temp` one is not optional. sdkmanager unpacks inside the SDK root before
moving the result into place, so redirecting only the destination fills the
internal disk and fails halfway through with `No space left on device`.

**The AVD itself cannot live on exFAT.** The system image is only read and sits
on the drive happily, but the emulator locks its AVD directory using hard links,
and exFAT has none -- `ln` there returns `Operation not supported`. The symptom
gives nothing away: the emulator burns 45% of a core indefinitely, never opens
its console or adb ports, never creates a data partition, and leaves a
`snapshot.lock.tmp-*` file behind. So the AVD lives in an APFS sparse image on
the same drive, which costs only what it uses:

    hdiutil create -type SPARSE -fs APFS -size 60g -volname MultiframeAVD \
        "/Volumes/Plex Files/AndroidEmulator/avd-store"
    hdiutil attach "/Volumes/Plex Files/AndroidEmulator/avd-store.sparseimage"

That has to be attached before the emulator will start; it mounts at
`/Volumes/MultiframeAVD`.

    export ANDROID_AVD_HOME="/Volumes/MultiframeAVD/avd"
    $HOME/Library/Android/sdk/emulator/emulator -avd multiframe36 \
        -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect

First cold boot takes several minutes: software rendering, and the images are on
a USB drive.

**What it can answer:** anything that is framework behaviour rather than camera
behaviour -- EXIF round trips, MediaStore, the mosaic canvas and its writing,
the raw ring's logic, and the Compose tests.

**What it cannot:** the camera path, since there is no RAW-capable virtual
camera -- those tests bail out by design, and one of them
(`ColorCalibrationDeviceTest`) reports as skipped because the emulator advertises
a camera id with no device behind it. And **every timing it reports is
meaningless.** The ring's 30 fps copy budget and the merge and develop numbers
are claims about a phone; measuring them here would quietly replace measurements
with fiction.

On the emulator the suite is one short: `ColorCalibrationDeviceTest` skips for
want of a camera. On a phone with a camera, and with the screen awake, all 95
run — a locked screen skips the fourteen Compose tests by design, which is the
mechanism working rather than a failure.

## What the instrumentation tests cover

The instrumentation tests exercise the native raw ring buffer -- its rotation,
locking and whether a 25 MB frame copy keeps up with 30 fps. They use synthetic
frames, so they need no camera permission and no scene, but the timings they
assert are only meaningful on real hardware. Reports land in
`~/Library/Caches/MultiframeBuild/app/reports/`.
