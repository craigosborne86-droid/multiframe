# Multiframe — System Context

## Mission

Multiframe is a pure computational photography engine for Android. Every pixel
in its output is a function of measured photon counts. No generative AI, no
neural networks, no cloud processing. The user promise: "If you see it in the
picture, the sensor saw it in the scene."

## Architecture

- **Language:** Kotlin (pipeline + UI) and C++20 (performance-critical paths).
- **UI:** 100% Jetpack Compose, dark-only, single-activity.
- **Camera:** Camera2 API via CameraX interop. Direct `CameraCaptureSession`
  control for ZSL and DCG burst modes.
- **The shutter:** a ZSL press locks the frames and returns in microseconds
  (`ZslCapture.take`); the merge, render and write run behind the viewfinder
  in the screen's develop queue (`ZslCapture.develop`), one shot at a time,
  and the shutter is never busy for it. The ring gives each frame's slot
  back as the merge reads it, and its mapping outlives a close while a shot
  holds slots. Do not put the develop back on the shutter's path. See
  `docs/REFERENCE.md` §3.7.
- **Exposure in AUTO:** the meter is bounded by a shutter cap (1/60 s,
  `ShutterCap`) that takes the exposure by hand only when the meter overruns
  it and hands it back when it can. Do not lengthen `AUTO_EXPOSURE_NS` past
  the frame interval; that is no cap at all. The highlight guard carries a
  standing bias of a stop (`HIGHLIGHT_BIAS_STOPS`) that the develop's
  auto-exposure and the filmic shoulder spend on the highlights; do not
  remove it to "fix" a dark preview. **The guard judges the scene, not the
  frame it darkened** (`ExposureStrategy.Guard`/`nextGuard`, stateful, the
  bright end read back up by the pull in force, with hysteresis): a guard
  that decides from each frame alone oscillates with a period of two
  decisions and the merge turns the swing into green pictures. The cap's
  follower pairs a frame with the result of the same sensor timestamp,
  never a neighbour's. `ShutterCap.retarget` is told stops *darker*. A
  manual exposure -- PRO's or the read-noise profiler's -- releases the
  cap's hold; the hold never outranks a manual setting in the request.
- **The merge scales light, not the pedestal.** A frame at another gain is
  normalised as `black + (raw − black) / g` (`bayer_merge.cpp`,
  `BayerMerger.kt`); dividing the raw code drags the merge under black and
  the white balance turns that green. The reference frame is the newest
  (lowest-ISO only under DCG). The DNG carries the guard's pull undone in
  `BaselineExposure`; do not brighten the raw pixels instead.
- **The shoulder's white is where the sensor stops**: `white = gain`
  (`DevelopParams.withGain`, `Tone.h resolveShoulder`), so a clipped pixel
  is display white. A constant white point over nominal renders the clip
  grey and the whole picture hazy; do not put one back.
- **The optics correction crops what it cannot read** (`LensDistortion.
  validInset`, `Optics.applyCropped`). Do not invent border pixels --
  repeating the edge made ribs, mirroring made reflections.
- **The mosaic's detail band is blended by seam, not by weight.** Every
  detail-band pixel comes from one tile (`MosaicCanvas::cutSeam`); the
  coarse bands feather. Do not reintroduce averaging in the detail band.
  **Every tile is refined against the canvas before it is drawn**
  (`MosaicCanvas::RefineTile`, `MosaicPlacement`): registration on a
  320-pixel proxy and the attitude are both a hundred pixels out on a
  4080-pixel tile, and no blend hides that. Do not draw a tile at its
  first placement.
- **The shutter refuses what the ring cannot hold** (`lockedFrames() +
  frames > ringCapacity` → reject haptic) and waits for the ring to refill
  rather than take a short burst. Do not let a press take one frame.
- **Native:** NEON-vectorized merge, alignment, shading, rotation, JPEG encode
  (vendored libjpeg-turbo). All native code is in `app/src/main/cpp/`.
- **No external image libraries.** No OpenCV, no RenderScript, no third-party
  image processing. Alignment, feature matching, homography, demosaic, tone
  mapping, color science — all hand-written.
- **Build:** compileSdk 37, minSdk 33, targetSdk 36, NDK 28.2, arm64-v8a only.

## Coding Standards

### Kotlin

- Functions that touch camera hardware or I/O run on `Dispatchers.IO`.
- Pipeline computation runs on `Dispatchers.Default`.
- The UI thread never blocks on pipeline work. Zero operations > 16ms on main.
- `CameraCapabilities` is the single source of truth for what the hardware
  supports. Every feature toggle checks capabilities before offering itself.
- `AppSettings` is the single source of truth for user preferences. Settings
  round-trip through `SharedPreferences` and are restored on launch.

### C++ / NDK

- All native functions are JNI entry points in their respective `.cpp` files.
- Memory-mapped buffers (`AHardwareBuffer`, `mmap`) are the transport between
  the camera HAL, the native ring, and the Kotlin pipeline. No unnecessary
  copies.
- NEON intrinsics are used for inner loops operating on pixel data. Write scalar
  fallbacks only if there is a real non-arm64 target (there is not currently).
- `-ffast-math` is enabled. Be aware that this allows the compiler to
  reassociate and fuse operations, producing last-place differences between
  logically identical code in different surroundings. The shading speed test
  documents this. Parity bounds should be stated as `< 1e-5` on the linear
  plane, not as bit equality, unless the code path is truly identical.

### Compose UI

- **Design tokens live in `Theme.kt`.** Colors are `Ink.*` tokens. Typography
  is `Type.*` styles. Do not write raw `Color(0xFF...)` or `fontSize = 13.sp`
  in composables — use the tokens.
- **Haptics are dispatched through the `Haptics` object in `Theme.kt`.** Do not
  call `performHapticFeedback` directly. Every haptic feedback point is
  catalogued in `docs/REFERENCE.md` section 1.3.
- **No haptics during capture.** Vibration motor activation during the burst
  would introduce motion blur.
- **`contentDescription` on every interactive element.** Minimum touch target
  48dp. This is a floor, not a goal.
- **Shape distinguishes function.** Settings are pills (`RoundedCornerShape
  (24.dp)`). Actions that write files are squared (`RoundedCornerShape(8.dp)`).
  Lenses are circles. Do not introduce a fourth shape language.

## Palette Rules (The Ink System)

1. **Nothing coloured sits on top of the photograph.** The three documented
   exceptions are `Ink.Level` (green level gauge), `Ink.Clipping` (red
   highlight warning) and `Ink.Peaking` (cyan focus peaking while manual
   focus is engaged). All are transient states about the photograph, not the
   interface.
2. **Selection is shown by weight, not hue.** Active = `Bone` fill. Inactive =
   `Pane` background + `Hairline` border.
3. **`Amber` is spent only on numerical readings and live values.** Not on
   labels, backgrounds, borders, or icons.
4. **`Danger` and `Confirm` are never on the viewfinder** during normal
   operation. They exist for gallery actions and Super Res tile guidance only.
5. A value that is a live reading gets `Amber`. The word for "no reading" (e.g.,
   "OFF") gets `Muted`.

## Hardware Assumptions — Verify, Never Assume

**Critical:** No code may assume a hardware capability exists without a runtime
check. Every feature is gated:

- RAW output: `CameraCapabilities.supportsRaw`
- Manual sensor: `CameraCapabilities.hasManualSensor`
- ISO range: `CameraCapabilities.hasIsoRange`
- DCG bracketing: `CameraCapabilities.supportsBracketing`
- Multiple lenses: `LensCatalog.rear(lenses).size > 1`
- Telephoto RAW: `LensProfile.supportsRaw` for the specific physical camera
- Distortion data: `LensProfile.distortionCoefficients != null`

If a feature is unavailable, the corresponding UI control is **hidden** (not
disabled, not greyed out — absent). The user never sees a control they cannot
use.

When probing hardware capabilities:

- Wrap `Camera2` calls that may throw on specific vendors in try-catch. Samsung,
  OnePlus, and Xiaomi have known quirks documented in `docs/REFERENCE.md`
  section 2.2.1.
- Never apply a vendor workaround globally. Key it to `Build.MANUFACTURER`.
- Cache `LensProfile` results in `SharedPreferences`. Invalidate when the
  camera ID list changes.

## Pipeline Invariants

The raw develop pipeline order is fixed and documented in `docs/REFERENCE.md`
section 3.1:

```
Black subtract -> Hot pixel -> Lens shading -> White balance -> Demosaic
-> Color matrix -> Tone curve -> sRGB gamma -> Chroma smooth -> Defringe -> Sharpen
```

Do not reorder these steps. Each operates on the output of the previous step.
Inserting a new step requires documenting it in `REFERENCE.md` with its position
and the mathematical justification for that position.

The merge invariant: when `gainScale = 1.0f` for all frames, the merge output
is identical to the pre-DCG merge. The `DcgDeviceTest.aaNullDcgRatio1IsIdentical
ToUniformMerge` test is the proof. Any change to the merge path must preserve
this invariant.

## Testing Standards

- **Unit tests** (`app/src/test/`): JVM-only, no Android framework. Test pure
  functions: exposure strategy, control bar construction, merge math, settings
  round-trip, capture readout formatting.
- **Device tests** (`app/src/androidTest/`): Run on physical hardware. Test
  native parity (Kotlin vs. C++), speed benchmarks (with A/A null validation),
  camera capability probing, DCG correctness.
- **Every speed test has an A/A null.** Before claiming "A is faster than B,"
  the harness must fail to distinguish A from A. If the null separates, the
  harness has a bias and the comparison is void.
- **Parity tests use stated bounds, not bit equality.** `-ffast-math` produces
  last-place differences. The bound is `< 1e-5` on the linear plane unless the
  code paths are structurally identical.

## Documentation

- **`docs/PRD.md`**: Product requirements, feature specifications, acceptance
  criteria. The "what" and "why."
- **`docs/REFERENCE.md`**: Engineering and design reference. Color tokens,
  typography, haptics, hardware matrix, pipeline math. The "how."
- **`CLAUDE.md`** (this file): System context and coding standards. The
  invariants that prevent regression.
- **`SUPERRES.md`**: Telephoto mosaic super-resolution system documentation.
- **`BASELINE.md`**: Performance baseline measurements and test methodology.

When these documents and a code comment disagree, the document is correct and
the code comment should be updated.

## What Not to Do

- **Do not add generative AI.** No neural networks for denoise, super-res,
  demosaic, or any image processing. If computational denoise is ever added, it
  must be wavelet or BM3D-family.
- **Do not add cloud features.** No network permission, no upload, no sync.
- **Do not skip haptics.** Every state change in the UI has a defined haptic
  response. See `docs/REFERENCE.md` section 1.3.
- **Do not use raw color/size literals in composables.** Use `Ink.*` and
  `Type.*` tokens.
- **Do not assume hardware features exist.** Gate on `CameraCapabilities` and
  `LensProfile`. Hide controls for unavailable features.
- **Do not reorder pipeline steps** without updating `docs/REFERENCE.md` with
  mathematical justification.
- **Do not add `sleep()`, `Thread.sleep()`, or busy-wait loops** in pipeline
  code. Use coroutine suspension or callbacks.
- **Do not commit code that breaks the A/A null invariant** in any speed test
  or parity test.
- **Do not add third-party image processing libraries.** The hand-written
  pipeline is a deliberate architectural choice.
