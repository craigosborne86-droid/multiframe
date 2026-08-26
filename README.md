# Multiframe

An Android computational camera. Every shutter press merges a burst of raw
sensor frames — aligned, weighted and combined before demosaicing — so that both
the DNG and the JPEG carry the benefit rather than one being a consolation
prize.

The working name will not survive; see [NAMES.md](NAMES.md).

## The argument

The computation is spent getting a **cleaner negative**, not a more *processed
picture*.

That is the whole positioning, and it decides most of the design. Stacking
frames buys signal-to-noise, which is spent on highlight headroom and shadow
detail rather than on local tone mapping. There is no adaptive contrast, no
saturation boost, and no spatial denoising anywhere. What there *is* is a proper
rendering curve — "no curve" is not neutrality, it is a washed-out picture, and
every camera ever made applies one.

## What it does

**Zero-shutter-lag raw.** Full-resolution RAW_SENSOR streams continuously at
30 fps into a ring in native memory, so the frames already exist when the button
is pressed. Verified on a Pixel 9 Pro XL: 89 frames in three seconds with none
dropped, a shutter handover of **59 microseconds**, and an eight-frame burst
spanning **235 ms** against 5.6 seconds for sequential capture.

**A raw pipeline of its own.** Defective-pixel suppression, lens shading, a
gradient-corrected demosaic, colour from the sensor's own calibration rather
than the vendor's rendering matrix, a filmic tone curve, and restrained capture
sharpening. Each stage exists in Kotlin and C++, pinned together by a parity
test that holds them to 0/255 — and the burst aligner to an identical
displacement field across 3024 tiles.

**Every lens.** All five physical cameras, labelled in millimetres because that
is what tells a photographer what the frame will look like.

**Exposure that uses the burst.** Averaging N frames improves signal-to-noise by
sqrt(N), which is `0.5 * log2(N)` stops of exposure that can be given up to
protect highlights. Eight frames buys 1.5 stops for nothing.

**Files that say how they were taken.** `Bitmap.compress` writes no metadata at
all, so the JPEG gets camera, lens, exposure, ISO, aperture and date written
from the same capture result the DNG's metadata comes from — including the frame
count, which is the one thing about these files no other tag records.

**Super-resolution by telephoto mosaic.** Sweep the 110mm across the 24mm
framing and stitch: 263 MP from 7×7 tiles, with no invented detail. See
[SUPERRES.md](SUPERRES.md) — including the limitation, which is real.

## Building

See [BUILD.md](BUILD.md). Short version:

    export JAVA_HOME="$HOME/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home"
    ./gradlew :app:assembleDebug
    ./gradlew :app:testDebugUnitTest          # 332 tests, no device
    ./gradlew :app:connectedDebugAndroidTest  # 99 tests, needs a device

## How the code is arranged

Everything of consequence is in `app/src/main/java/dev/multiframe/camera/pipeline/`.

| Area | Files |
|---|---|
| Capture | `ZslRawStream`, `RawRing`, `ZslCapture`, `RawBurstCapture` |
| Merge | `bayer_merge.cpp`, `NativeMerge`, `Aligner`, `Align.cpp`, `BayerMerger` |
| Develop | `Demosaic`, `ToneCurve`, `ColorScience`, `LensShading`, `HotPixels`, `Sharpen`, `Defringe` |
| Output | `ImageSaver`, `ExifWriter`, `RecentCapture`, `Rotate.cpp`, `Jpeg.cpp`, `NativeJpeg` |
| Decisions | `ZslPolicy`, `ExposureStrategy`, `CaptureMode`, `MemoryPressure` |
| Mosaic | `Homography`, `FeatureMatcher`, `MosaicPlanner`, `MosaicAssembler`, `MosaicRefiner`, `Mosaic.cpp` |

One third-party dependency, and it is native: **libjpeg-turbo**, vendored under
`app/src/main/cpp/third_party/` with its provenance recorded beside it. The NDK
ships no JPEG encoder, and `Bitmap.compress` reaches Skia's copy of this same
library through an interface that offers no handle on it — one call, one thread.
Having the library directly is what lets a capture be encoded in strips across
every core, which took the encode from about 240 ms to under 100. Everything
else in `cpp/` is written here.

Two conventions run through it.

**Decisions are kept free of framework types** so they can be tested against
hardware this device is not — `ZslPolicy` holds no Camera2 types, `TouchFocus`
holds no `android.graphics.Rect`. That second one was learned the hard way: the
framework `Rect` is a stub in unit tests whose every method returns zero, so
tests written against it compare zeroes and pass while proving nothing.

**Nothing image-sized touches the Java heap.** The process is capped at 256 MB
and a single raw frame is 25 MB. Buffers are `mmap`'d, camera frames are read in
place through their own direct buffers, and the DNG is written straight from
native memory.

## Reading the history

[SESSION-LOG.md](SESSION-LOG.md) is the honest account: what each phase changed,
what was measured, and a running list of mistakes that produced confident wrong
answers. It is worth more than this file. Among them: a focus metric that rated
a *defocused* frame as sharper, a demosaic that was destroying 43% of fine
detail, a ratio test that kept precisely its worst matches, and two performance
claims that had to be withdrawn for sitting inside the measurement noise.

**A note on what is verified.** All 99 device tests run on the phone — a Pixel
9 Pro XL on Android 17 — with none skipped *provided the screen is awake*,
including the fourteen Compose tests
that spent most of this project's life reporting as skipped, because an activity
behind a keyguard never resumes and the development device was locked.

An emulator is kept for working without the phone. It settles anything that is
framework behaviour rather than camera behaviour, and it is never used for a
timing: every number in the log is measured on the device, where it means
something.
