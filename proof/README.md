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

## Merged raw (DNG that carries the burst)

The single-frame DNG above is one unprocessed frame. This is the merged one:
a burst of raw frames aligned and combined in the Bayer domain, written back
out as a DNG.

| File | |
|---|---|
| `MF_20260824_150646_raw.dng` | single raw frame |
| `MF_20260824_150707_merged_8f.dng` | 8 raw frames merged |
| `p6_raw_merge.png` | dark flat region, green plane, 14x gain on both |

Both are valid DNG 1.4.0.0, 4080x3072, 16-bit, photometric 32803 (CFA),
CFAPattern GBRG, white level 1023. Captured 20 s apart with the phone
stationary (accelerometer 9.74/1.14 then 9.74/1.17).

### Measured

    flat-region noise, per CFA plane (codes)
      G   0.948 -> 0.482   ratio 0.508
      B   0.942 -> 0.447   ratio 0.475
      R   0.940 -> 0.423   ratio 0.450
      G2  0.946 -> 0.481   ratio 0.508
    mean ratio 0.485  ->  2.06x noise reduction

    framesCaptured 8, framesMerged 8, meanContribution 0.988
    capture 1716 ms, merge 4907 ms, write 199 ms

2.06x against a theoretical 2.83x for eight frames. The shortfall is expected
here: single-frame noise is only 0.95 codes, essentially at the quantisation
floor, and sensor fixed-pattern noise is identical in every frame so averaging
cannot remove it.

### Two measurements I got wrong first

Recorded because both produced confident, wrong numbers.

**Strip offsets.** The first extraction reported 4.25x improvement and noise
sigmas above 600 codes in a 10-bit file. `StripOffsets` in these DNGs is an
array of 3072 entries (`RowsPerStrip` = 1), not a single value; the tag holds a
pointer to the offset table. Reading from that pointer parses the table itself
as pixels. Tells were a same-scene correlation of 0.54 and a merged blue plane
that came out almost perfectly flat.

**Texture counted as noise.** The corrected extraction then reported only 1.22x.
That metric takes the residual against neighbouring pixels, which captures scene
detail as well as noise, and merging correctly preserves detail. Restricting the
measurement to the flattest 20% of blocks gives the real figure of 2.06x.

### Sequential capture is not a hardware burst

CameraX cannot stream RAW_SENSOR through ImageAnalysis, so frames are requested
one at a time. Measured arrival times span 5.6 seconds at roughly 870 ms apart:

    54.335  54.755  55.620  56.481  57.349  58.219  59.081  59.970

A real burst is tens of milliseconds apart. Frame agreement stayed high here
(contribution 0.988) so nothing was rejected, but this window is far too long
for moving subjects and rules out zero shutter lag for raw. Closing it means a
parallel Camera2 session with ImageReader(RAW_SENSOR) and captureBurst.

Also note RAW_JPEG delivers two images per capture through separate callbacks,
with the JPEG usually arriving first. Resuming on the first one crashed with
"Already resumed"; the raw is the second.

## Raw stream capability (probed 2026-08-24)

Whether a continuous zero-shutter-lag raw ring is feasible depends on the
sensor's raw stream configuration, not on guesswork:

    RAW 4080x3072: minFrameDuration 33333us (30.0 fps)  stall 0us
    RAW 4080x2288: minFrameDuration 16666us (60.0 fps)  stall 0us
    RAW 2032x1536: minFrameDuration 16666us (60.0 fps)  stall 0us
    RAW 2016x1136: minFrameDuration 16666us (60.0 fps)  stall 0us

The stall duration of zero is the important figure. A non-zero stall would mean
queuing a raw capture blocks the other streams, which would make continuous raw
streaming impractical. At zero, full-resolution raw can run continuously at
30 fps alongside preview.

Consequence: an 8-frame raw burst spans 233 ms at 30 fps, against the 5.6 s
window the current sequential takePicture approach produces. That is a 24x
tighter capture window, and it is what makes zero shutter lag possible for raw.

## Unified raw front end

One raw merge now feeds both outputs, matching the pipeline shape Indigo
describes: raw frames are aligned and merged once, then the merged CFA data
goes to the DNG untouched and through a develop stage to the JPEG. The two
outputs carry identical merge benefit instead of coming from separate
pipelines.

Back end: bilinear demosaic via a generic 3x3 gather (works for any CFA
arrangement), white balance from COLOR_CORRECTION_GAINS, sensor-to-sRGB via
COLOR_CORRECTION_TRANSFORM, global auto exposure, the same restrained shoulder
curve, then sRGB encode. No local tone mapping, no sharpening, no saturation
boost.

    raw merge -> DNG + JPEG, 8 frames
    capture 1937 ms  merge 4245 ms  develop 1960 ms  write 179 ms
    meanContribution 0.979

### Auto exposure was necessary

Rendering linear sensor data with a fixed gain leaves everything crushed. The
first unified render came out at a median luma of 17 with 72% of pixels below
32. Measuring a global exposure multiplier from the frame (92nd percentile of
the green sites mapped to 0.62 linear) fixed it:

    median luma      17 -> 62
    below 32       0.723 -> 0.208
    above 250          -> 0.040

`p7_autoexp.png` shows the result. Colour is correct: warm brickwork, blue sky
with cloud detail retained, green foliage, neutral wall.

### An OOM on the way

The first attempt wrote the DNG then died allocating 50 MB for the render
buffer. The accumulator holds two full-resolution float buffers, 100 MB at
12.5 MP, and they were still live when the render buffers were requested.
Two fixes: `BayerAccumulator.release()` frees them once the merge is done, and
the developer renders into the Bitmap in horizontal bands rather than building
a full-resolution IntArray.

### Heap limits on this device

    dalvik.vm.heapgrowthlimit   256m     current ceiling
    dalvik.vm.heapsize          512m     what largeHeap would raise it to
    MemTotal                    15.2 GB  physical RAM
    MemAvailable                1.2 GB   at time of measurement

Process after a raw burst: Dalvik heap 42 MB of a 256 MB limit, native heap
16 MB, graphics 89 MB, total PSS 249 MB.
