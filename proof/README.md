# On-device evidence

Device: Pixel 9 Pro XL (komodo), Android 17 / API 37.

## Phase 1

| File | Shows |
|---|---|
| `p1_permission.png` | Runtime CAMERA permission dialog |
| `p1_viewfinder.png` | Live viewfinder rendering real camera content |
| `MF_20260824_082024.jpg` | Capture saved by the app, pulled off the device |
| `capture_exif_applied.jpg` | Same capture with EXIF orientation applied |

The Phase 1 images look rotated. That is the phone's physical pose, not a
pipeline fault: the accelerometer read x=-9.72, y=0.22 m/s^2 (lying on its
side) while auto-rotate was off. EXIF Orientation=6 and MediaStore
orientation=90 agree with the preview.

## Phase 2

| File | Shows |
|---|---|
| `p2b.png` | Viewfinder with MERGE / A/B / frame-count / EV controls |
| `MF_20260824_120550_ab_single.jpg` | Reference frame alone |
| `MF_20260824_120550_ab_merged.jpg` | 8-frame merge of the *same* burst |
| `p2_ab_comparison.png` | Shadow-region crop, side by side |

Both A/B images come from one `BurstBuffer.snapshot()`, so scene, exposure and
tone curve are identical and the only variable is the merge.

### Measured

    same-scene check    correlation 0.99920, mean|diff| 2.24
    mean level          single 95.75   merged 95.59
    global std dev      single 81.309  merged 81.308   (contrast preserved)

    high-frequency noise sigma    3.651 -> 2.794   1.31x
    median local sigma (16x16)    5.291 -> 3.604   1.47x
    shadow noise sigma (<60)      3.588 -> 1.982   1.81x
    JPEG size                   927,386 -> 669,734 bytes

    on-device timing    align 2456 ms, merge 1449 ms, 8 frames at 2048x1536
    mean frame contribution 0.993

### A discarded measurement

An earlier A/B attempt (12:00 and 12:01) compared two separately triggered
shots. The phone was moved between them, so the two frames showed different
scenes and the resulting noise figures were meaningless. Those files were
deleted and the A/B mode was added specifically to remove that confound.

## Phase 3 — manual controls

Capabilities read from the device at runtime (nothing hard-coded):

    manual  ISO 22-11277  26us-16000ms  focus 0-9.5D  RAW

| File | Shows |
|---|---|
| `p3_pro_panel.png` | PRO panel: capability line, AE/AF/ISP toggles, EV slider, WB row |
| `p3_iso_high.png` | Manual exposure engaged, ISO 6712 at 1/28705 |
| `MF_20260824_121825_ab_*.jpg` | High-ISO A/B pair |

### Manual controls verified by measured hardware response

Shutter slider swept, viewfinder mean luminance:

    fastest   92.63
    mid      251.21
    slow     252.26

ISO swept with shutter pinned fast:

    low       85.61
    mid      151.26
    high     174.42

Both reach the hardware. Screenshots alone would not have proved this.

### Orientation fix verified

Saved files are 1536x2048 (portrait) with `rot=90` logged, computed from
`SENSOR_ORIENTATION` plus `OrientationEventListener`, so captures come out
upright regardless of the system rotation lock.

### Known defect found by these controls

At ISO 6712 the merge is much less effective than at low ISO:

    low ISO   shadow noise 3.588 -> 1.982   1.81x   mean contribution 0.993
    ISO 6712  hf noise    11.278 -> 8.636   1.31x   mean contribution 0.740

`MergeParams.robustnessSigma` is a fixed constant, but sensor noise is
signal-dependent. At high ISO the weighting reads noise as subject motion and
rejects frames that should have been merged. The fix is to scale sigma with
local signal level (shot noise grows as the square root of signal) rather than
holding it constant.

## Robustness fix

`MergeParams.robustnessSigma` (a fixed constant) was replaced with a noise
model estimated from the burst itself: frame-to-frame differences in a static
scene *are* noise, so taking a median of those differences per brightness bin
gives a signal-dependent sigma with no ISO or exposure metadata needed. Weight
is now 1.0 inside the expected noise envelope and falls off as the variance
ratio beyond it, rather than penalising every difference.

Same ISO 6712 settings, same scene, same-burst A/B both times:

| | noise reduction | mean frame contribution |
|---|---|---|
| before | 1.31x (ratio 0.766) | 0.740 |
| after  | 1.89x (ratio 0.529) | 0.919 |

Contrast preserved (global std ratio 0.987). `p4_highiso_fixed.png` shows the
crop. Single-frame noise differs between the two runs because the light
changed, so the *ratios* are the comparable figure, not the absolute sigmas.

Unit tests also improved, and a ghosting test was added to confirm the looser
threshold did not reintroduce smearing:

    robust merge, default params   0.595 -> 0.365   (ideal 1/sqrt(8) = 0.354)
    worst ghost deviation          0.072            (plain averaging ~0.5)

## Phase 4 — portability

Four real assumptions were found by audit and removed.

| Assumption | Was | Now |
|---|---|---|
| ISP noise reduction / edge can be switched off | Requested unconditionally | Gated on `NOISE_REDUCTION_AVAILABLE_MODES` and `EDGE_AVAILABLE_EDGE_MODES` |
| Autofocus supports CONTINUOUS_PICTURE | Requested unconditionally | Falls back CONTINUOUS_PICTURE -> AUTO -> none, from `CONTROL_AF_AVAILABLE_MODES` |
| A back camera exists | `DEFAULT_BACK_CAMERA` assumed | Tries back, then front, then reports no camera |
| 12 frames at 2048x1536 always fits | Fixed constants | Both derived from `Runtime.maxMemory()` |

The earlier code comment claiming unsupported modes are "ignored" was wrong;
requesting an unadvertised mode is not guaranteed to be benign, so it is now
checked.

`hasManualFocus` also now requires an AF-off mode, not just a focusable lens:
a lens that can focus but exposes no way to disable autofocus cannot be driven
manually.

### Heap-derived sizing

Reducing frame count alone was not enough. On a small heap the merge's own
working set (about 20 bytes per pixel) can exceed the budget by itself, so
resolution comes down too. Memory sets the ceiling and a latency cap bounds it
from above, since align and merge cost scales with pixel count.

    heap 96MB  -> 1280x960    8 frames
    heap 192MB -> 2048x1536   5 frames
    heap 384MB -> 2048x1536   8 frames
    heap 768MB -> 2048x1536   (capped for latency, not memory)

Verified on the Pixel 9 Pro XL, which reports a 256MB heap:

    Capabilities: manual  ISO 22-11277  26us-16000ms  focus 0-9.5D  RAW
    Heap max 256MB, analysis 2048x1536

The frame-count control cycles 2 -> 4 -> 8 -> 1 and never offers 12, because
the ring can only afford 8 frames on this heap. See `p4_frame_cycle.png`.

### Remaining known assumptions

- The YUV to RGB conversion assumes full-range BT.601, and the merge assumes
  roughly sRGB gamma 2.2. `YUV_420_888` does not expose its range or transfer
  function, so a device outputting limited-range data would show slightly
  raised blacks. Not currently detectable through the CameraX API.
- 30 unit tests pass, but the non-Pixel hardware profiles are simulated
  `CameraCapabilities` values, not real devices. They prove the app degrades as
  intended given those inputs; they do not prove the inputs match real hardware.

## Phase 5 — Play Store readiness

### Target API level, confirmed live 2026-08-24

Play Console Help states new apps and updates must target **Android 16
(API 36)** or higher from **31 August 2026** — seven days from this check. An
extension to 1 November 2026 can be requested.

`targetSdk` is 36, so the app complies. `compileSdk` is 37 only because
AndroidX 1.19.x demands it; that is independent of the target level. Raising
`targetSdk` to 37 would opt into untested Android 17 behaviour changes and is
not required.

### Release build

| Artefact | Size |
|---|---|
| `app-debug.apk` | 64 MB |
| `app-release.apk` | 2.5 MB |
| `app-release.aab` | 3.4 MB |

R8 and resource shrinking are enabled. Signature verified with `apksigner`:
APK Signature Scheme v2, single signer.

Signing reads `keystore.properties`, which is gitignored along with `*.jks`.
A template is committed. If the file is absent the release build stays unsigned
rather than failing, so a fresh clone still compiles.

The release build was installed and exercised on device — minification does not
break the pipeline:

    MergeStats(framesUsed=8, meanContribution=0.958, alignMillis=3002, mergeMillis=525)

Merge time dropped from ~1500 ms in debug to 525 ms in release.

### Permissions

The shipped manifest declares exactly one permission, `CAMERA`, confirmed by
`aapt2 dump badging` on the release APK. No `INTERNET`, so the privacy claim is
enforced by the manifest rather than merely asserted. No `READ_MEDIA_IMAGES`,
so Play's Photo and Video Permissions policy does not apply.

### Privacy policy

`store/PRIVACY.md`. Play requires a
live HTTPS URL, not a PDF, in the Console *and* inside the app. The in-app half
is met by the About screen (`p5_about.png`), reachable from the `i` control.

### Icon

Original adaptive icon, three offset frames converging on a point, verified
rendering at launcher size (`p5_icon.png`).

### Name and icon, settled 2026-08-24

The app is **Multiframe**; `applicationId` `dev.multiframe.camera` is final.
Checked on Play: no app of that name exists. Nearest matches are *MultiFrames*
(plural, unrelated category) and a developer account *Multiframes* — a
discoverability nuisance, not an impersonation risk. The name is descriptive
rather than arbitrary, so it is weak as a trademark; noted as a commercial
trade-off, not a blocker.

Store assets generated from the same geometry as the in-app adaptive icon:

    store/assets/play_icon_512.png          512x512
    store/assets/play_feature_1024x500.png  1024x500

The store icon is scaled to fill more of the square than the adaptive icon,
which needs to stay inside the safe zone for launcher masking.
