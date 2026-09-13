# Multiframe — Engineering & Design Reference Pack

**Version:** 1.0
**Date:** 2026-09-10

This document is the authoritative reference for implementation. When this
document and a code comment disagree, this document is correct and the code
comment should be updated.

---

## 1. Design System & Joy Reference

### 1.1 The Ink Color System

#### 1.1.1 Philosophy

Two rules decide the palette:

1. **Nothing coloured sits on top of the photograph.** A saturated hue in
   peripheral vision shifts the user's perception of the colours in the image.
   A camera whose argument is neutral rendering cannot introduce that bias.

2. **Selection is shown by weight, not hue.** An active control is filled with
   `Bone`; an inactive one sits on `Pane`. The single accent (`Amber`) is spent
   exclusively on numerical readings and live values — the places an instrument
   has always spent its accent.

Two documented exceptions exist: `Level` (green, the level gauge) and `Clipping`
(red, blown highlights). Both are transient states about the photograph itself,
not about the interface.

#### 1.1.2 Palette Tokens

**Core palette (existing, do not modify):**

| Token | Hex | Alpha | Purpose |
|---|---|---|---|
| `Ground` | `#000000` | 100% | App background, viewfinder fallback |
| `Bone` | `#F2F0EA` | 100% | Primary text, active fill. Warm white (paper, not light leak) |
| `Pane` | `#141A1F` | 85% | Inactive control background. Translucent over image |
| `Hairline` | `#FFFFFF` | 15% | Control borders. Separates from dark photographs |
| `Muted` | `#9BA1A7` | 100% | Secondary text, labels. Present, not competing |
| `Amber` | `#FFB020` | 100% | The one accent. Values, sliders, live readings only |
| `Panel` | `#0A0C0E` | 94% | PRO panel background. Opaque enough to read controls |
| `OnBone` | `#0B0D0F` | 100% | Text on a Bone fill |
| `OnBoneMuted` | `#06121F` | 80% | Secondary text on Bone fill (lens zoom label) |
| `Edge` | `#FFFFFF` | 40% | Border of image-containing elements (thumbnail) |

**Readout and overlay tokens (existing, do not modify):**

| Token | Hex | Alpha | Purpose |
|---|---|---|---|
| `Readout` | `#000000` | 80% | Behind live text that must be legible over any image |
| `Faint` | `#000000` | 40% | Behind graphics with inherent contrast |
| `ScrimTop` | `#000000` | 65% | Gradient ground for top control row |
| `ScrimBottom` | `#000000` | 72% | Gradient ground for bottom controls |

**Guide tokens (existing, do not modify):**

| Token | Hex | Alpha | Purpose |
|---|---|---|---|
| `Guide` | `#FFFFFF` | 30% | Composition guide lines |
| `GuideStrong` | `#FFFFFF` | 40% | Emphasized guide lines |
| `GuideFaint` | `#FFFFFF` | 10% | Subtle reference lines |
| `GuideMid` | `#FFFFFF` | 27% | Intermediate guides |
| `GuideText` | `#FFFFFF` | 73% | Text drawn on the viewfinder |
| `GuideBright` | `#FFFFFF` | 80% | Strong viewfinder annotations |

**Exception tokens (existing, documented exceptions to rule 1):**

| Token | Hex | Purpose |
|---|---|---|
| `Level` | `#6FE39A` | Level gauge at rest. Appears only when level is achieved |
| `Clipping` | `#FF6B6B` | Blown highlight indicator. Appears only when clipping |

**Sheet tokens (existing, for the About sheet):**

| Token | Hex | Purpose |
|---|---|---|
| `Sheet` | `#06090C` | Sheet background (opaque, covers image) |
| `SheetBody` | `#D4DDE6` | Body text on sheet |
| `SheetMuted` | `#8A97A5` | Secondary text on sheet |
| `SheetCode` | `#161C22` | Code block background on sheet |

**New semantic tokens (to be added in Phase 1):**

| Token | Hex | Alpha | Purpose | Rule |
|---|---|---|---|---|
| `BoneDim` | `#F2F0EA` | 50% | Tertiary text, disabled labels | Same warmth as Bone, reduced presence |
| `AmberDim` | `#FFB020` | 40% | Pressed/focused state of Amber elements | Communicates interaction without full accent |
| `Danger` | `#E85B5B` | 100% | Destructive actions outside the viewfinder (delete in gallery) | Never on the viewfinder. Only in modal/gallery contexts |
| `Confirm` | `#5BC87A` | 100% | Success states outside the viewfinder (sweep tile lock) | Never on the viewfinder except during active sweep guidance |
| `PaneSolid` | `#0F1215` | 100% | Non-overlay screen backgrounds (gallery, settings) | For screens that don't sit on the photograph |

#### 1.1.3 Color Usage Rules

1. `Amber` is the **only** accent color. It appears on: numerical readings,
   slider thumbs, active slider fill endpoints, focus reticle, shutter ring
   during capture. It never appears on: labels, backgrounds, borders, icons.

2. When a control is **active/selected**: fill with `Bone`, text in `OnBone`.
   When **inactive**: background `Pane`, border `Hairline`, text `Bone`.

3. When a value is a **live reading** (a number the camera is reporting):
   `Amber`. When a value is **the word for not having a reading** (e.g., "OFF"):
   `Muted`. This prevents "TIMER OFF" from looking like "TIMER is running."

4. `Danger` and `Confirm` are **never** used on the viewfinder during normal
   operation. They appear only in: gallery delete confirmation, sweep tile
   guidance (during active sweep only), and error dialogs.

---

### 1.2 Typography Scale

#### 1.2.1 Type Tokens (as `TextStyle` objects in `Theme.kt`)

```
object Type {
    val Gauge       = TextStyle(fontSize=13.sp, fontFamily=Monospace, color=Ink.Amber)
    val GaugeLarge  = TextStyle(fontSize=26.sp, fontFamily=Monospace, color=Ink.Amber)
    val GaugeBone   = TextStyle(fontSize=13.sp, fontFamily=Monospace, color=Ink.Bone)
    val Label       = TextStyle(fontSize=11.sp, fontWeight=Medium, letterSpacing=0.5.sp, color=Ink.Muted)
    val Control     = TextStyle(fontSize=12.sp, fontWeight=Medium, letterSpacing=0.4.sp, color=Ink.Bone)
    val Section     = TextStyle(fontSize=10.sp, fontWeight=Medium, letterSpacing=1.2.sp, color=Ink.Muted)
    val Status      = TextStyle(fontSize=12.sp, fontFamily=Monospace, color=Color.White)
    val Countdown   = TextStyle(fontSize=76.sp, fontFamily=Monospace, color=Ink.Bone)
    val Instruction = TextStyle(fontSize=10.sp, fontWeight=Medium, letterSpacing=1.2.sp, color=Ink.Muted)
}
```

#### 1.2.2 Usage Rules

- **Monospace** is used exclusively for values the user reads as numbers:
  ISO, shutter speed, EV, zoom factor, tile count, coverage, focus distance,
  lens focal length, countdown digits. This is the instrument convention.

- **Proportional (default system font)** is used for labels, button text,
  section headers, instructions, and all prose. Proportional text at small sizes
  fits in tighter spaces and reads more naturally for words.

- **Never mix** a proportional label and a monospace label for the same kind of
  element. If one chip uses monospace for its value, all chips use monospace for
  their values.

- The `fontSize` values in the token table are the **canonical sizes**. Do not
  write `fontSize = 13.sp` directly in a composable — use `Type.Gauge.fontSize`
  or apply the style directly: `Text(text = value, style = Type.Gauge)`.

---

### 1.3 Haptic Feedback Mapping

#### 1.3.1 Implementation

All haptics are dispatched through a single helper that takes the Compose
`View` and the haptic constant. This centralizes the haptic vocabulary and
makes it testable (the helper can be a no-op in unit tests).

```kotlin
object Haptics {
    fun shutter(view: View)    = view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    fun tick(view: View)       = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    fun segment(view: View)    = view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
    fun reject(view: View)     = view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    fun confirm(view: View)    = view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
}
```

#### 1.3.2 Mapping Table

| Context | Trigger | Haptic Call | Rationale |
|---|---|---|---|
| Shutter pressed | `onClick` on shutter button | `Haptics.shutter` | Strong confirmation: a photograph was taken |
| Self-timer tick | Each countdown second | `Haptics.tick` | Light metronome, not distracting |
| Mode toggle | MERGE, DCG, ZSL, A/B, GUARD changed | `Haptics.segment` | Detent feel, like a mechanical switch |
| Cycle value | FRAMES, MODE, TIMER, GUIDES stepped | `Haptics.segment` | Same detent feel for any discrete step |
| Slider at detent | ISO at round number (100, 200, ...), EV at 0 | `Haptics.tick` | Subtle stop point, not every pixel of drag |
| Lens switch | New lens selected in strip | `Haptics.segment` | Camera is physically switching |
| Sweep tile locked | Reticle aligns with target dot | `Haptics.confirm` | "Got it" — eyes are on the subject |
| Sweep tile rejected | Alignment failed or motion blur | `Haptics.reject` | "Try again" — also eyes on subject |
| Error action | Tapping a disabled control, invalid state | `Haptics.reject` | Something went wrong |
| PRO panel open/close | Toggle PRO chip | (none) | Panel animation is sufficient feedback |
| Gallery delete confirmed | After confirmation dialog | `Haptics.confirm` | Acknowledge destructive action |

#### 1.3.3 Anti-Patterns

- **No haptics during capture.** The merge is running; the phone must be still.
  Any vibration motor activation during the burst would introduce motion blur.
- **No haptics on scroll.** Scrolling the control row, lens strip, or gallery
  grid produces no haptic feedback. This would be noise.
- **No continuous haptics.** Dragging a slider does not produce continuous
  vibration. Only the detent points fire.

---

### 1.4 Responsive Layout Rules

#### 1.4.1 Breakpoints

| Width | Layout Name | Description |
|---|---|---|
| < 600dp | `Phone` | Current layout. Controls in bottom column. Viewfinder full-width. |
| 600–840dp | `Expanded` | Phone landscape or small tablet. Viewfinder 2/3, controls sidebar 1/3. |
| > 840dp | `Wide` | Tablet or foldable outer display. Viewfinder left half, gallery filmstrip + controls right half. |

#### 1.4.2 Implementation

Use Jetpack Compose `WindowSizeClass` from `material3-window-size-class`:

```kotlin
val windowSizeClass = calculateWindowSizeClass(activity)
when (windowSizeClass.widthSizeClass) {
    WindowWidthSizeClass.Compact -> PhoneLayout(...)
    WindowWidthSizeClass.Medium  -> ExpandedLayout(...)
    WindowWidthSizeClass.Expanded -> WideLayout(...)
}
```

#### 1.4.3 Foldable Support

When `WindowInfoTracker.windowLayoutInfo` reports a `FoldingFeature` with
`state == HALF_OPENED`:

- Split at the fold boundary.
- Viewfinder on the display half closer to the rear cameras.
- Controls on the other half.
- This is "flex mode" / "tabletop mode."

#### 1.4.4 Orientation

- **Portrait** is the primary layout. All current dimensions are designed for
  portrait.
- **Landscape** on phones: equivalent to the `Expanded` layout (sidebar
  controls).
- The app does **not** force a specific orientation. It responds to the system
  orientation and adjusts layout.
- `OrientationTracker` handles EXIF rotation and capture orientation
  independently of UI orientation.

---

## 2. Hardware Capability & Fallback Matrix

### 2.1 LensProfile Specification

Each physical camera discovered by `LensCatalog` gets a `LensProfile`:

```kotlin
data class LensProfile(
    val cameraId: String,
    val parentLogicalId: String?,
    val focalLengthMm: Float,
    val equivalent35mm: Int,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val aperture: Float,
    val facing: Int,                    // CameraCharacteristics.LENS_FACING

    // Capabilities
    val supportsRaw: Boolean,
    val supportsManualSensor: Boolean,
    val supportsManualFocus: Boolean,
    val isoRange: IntRange?,            // null if no manual sensor
    val exposureRange: LongRange?,      // nanoseconds, null if no manual sensor
    val hasOis: Boolean,
    val afModes: List<Int>,
    val awbModes: List<Int>,

    // Optical calibration (may be null if not reported by hardware)
    val distortionCoefficients: FloatArray?,   // LENS_DISTORTION (Brown-Conrady)
    val intrinsicCalibration: FloatArray?,     // LENS_INTRINSIC_CALIBRATION
    val poseRotation: FloatArray?,             // LENS_POSE_ROTATION
    val poseTranslation: FloatArray?,          // LENS_POSE_TRANSLATION

    // DCG calibration (populated by background noise profiling, null until run)
    val dcgThresholdIso: Int?,                 // ISO at which read noise curve breaks
    val dcgRatio: Int?,                        // Optimal bracket ratio at threshold

    // Sensor geometry
    val activeArrayWidth: Int,
    val activeArrayHeight: Int,
    val pixelPitch: Float,                     // micrometers, derived from sensor size / active array
)
```

#### 2.1.1 Probing Sequence

1. `CameraManager.getCameraIdList()` enumerates all cameras.
2. For each ID, read `CameraCharacteristics`.
3. For logical cameras with `REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA`,
   enumerate `getPhysicalCameraIds()` and read characteristics for each.
4. Deduplicate by equivalent focal length (12% clustering, same as current
   `LensCatalog`).
5. Build `LensProfile` for each unique physical camera.
6. Cache in `SharedPreferences` (JSON serialized, keyed by camera ID list hash).
7. Invalidate cache when the camera ID list changes (e.g., after OS update).

#### 2.1.2 Probing is Non-Blocking

`LensProfile` discovery runs on `Dispatchers.IO` during app startup, in parallel
with camera session creation. The UI shows the viewfinder immediately with
whatever capabilities are known. Profiles arrive asynchronously and the UI
updates (e.g., DCG toggle appears once `supportsBracketing` is confirmed).

### 2.2 Fallback Rulebook

| Missing Capability | Detection | Behavior |
|---|---|---|
| No RAW output | `REQUEST_AVAILABLE_CAPABILITIES` lacks `RAW` | Hide: merge, DCG, DNG, super-res. JPEG-only mode. |
| No manual sensor | Lacks `MANUAL_SENSOR` capability | Hide: ISO/shutter sliders, manual exposure. AE auto only. |
| No manual focus | `LENS_INFO_MINIMUM_FOCUS_DISTANCE == null` or 0 | Hide: focus slider. AF auto only. |
| No ISO range | `SENSOR_INFO_SENSITIVITY_RANGE` null | Hide: DCG. No ISO bracketing possible. |
| No `setRepeatingBurst` | `session.setRepeatingBurst()` throws | Disable DCG permanently for this device. Log warning. |
| Single rear camera | `LensCatalog.rear(lenses).size <= 1` | Hide: lens strip, super-res. |
| Telephoto has no RAW | `LensProfile.supportsRaw == false` for telephoto | Super-res unavailable. Do not crash — show "unavailable" in sweep UI. |
| No OIS on telephoto | `LensProfile.hasOis == false` | Allow sweep but warn: "Stabilization unavailable. Use a tripod for best results." |
| No distortion data | `LENS_DISTORTION` not reported | Skip distortion correction. Log. Mosaic stitching proceeds without. |
| Low heap (< 256 MB) | `Runtime.maxMemory()` | Cap burst frames at 4, mosaic canvas at 20 MP. |

#### 2.2.1 Camera2 Vendor Quirk Handling

Known issues by vendor:

| Vendor | Quirk | Workaround |
|---|---|---|
| Samsung | `SENSOR_INFO_SENSITIVITY_RANGE` reports wider range than hardware supports; extreme ISOs produce black frames | Clamp ISO range to [50, 3200] on Samsung devices |
| OnePlus | `setRepeatingBurst()` silently drops to single-request on some firmware versions | Detect by checking `TotalCaptureResult` tag sequence; fall back to single-request if tags don't alternate |
| Xiaomi | Physical camera IDs not accessible without `CAMERA` permission grant on some MIUI versions | Retry `getPhysicalCameraIds()` after permission grant |
| Google | (reference implementation, no known quirks) | — |

Quirks are keyed by `Build.MANUFACTURER` and applied at `CameraCapabilities`
construction time. They are **never** applied globally — only to the specific
device class that needs them.

### 2.3 Non-Invasive DCG Calibration Strategy

#### 2.3.1 Automated Background Noise Profiling

**When it runs:**

- On first launch, or when a new physical camera ID is detected.
- During the first 3 seconds of preview startup, before the user has framed a
  shot.
- Only if `dcgThresholdIso` is null for this camera in the cached `LensProfile`.

**How it runs:**

1. The ZSL ring is already filling with preview frames at AE's chosen ISO.
2. Insert a brief calibration burst: 20 frames at ISO steps from `isoMin` to
   `isoMax` (logarithmically spaced), each with the shortest available exposure
   time.
3. Frames are captured into the existing ring buffer — no additional memory.
4. For each ISO step, compute temporal variance from a 256x256 center crop of
   two consecutive frames at the same ISO.
5. The variance values are fitted against ISO on a background thread.
6. The result (threshold ISO or "no threshold detected") is written to
   `SharedPreferences` and the `LensProfile` is updated.
7. Normal preview resumes. Total wall time: < 2 seconds. The user sees the
   viewfinder throughout.

**What the user sees:** Nothing. The viewfinder is live during calibration. There
is no dialog, no progress bar, no "calibrating..." message. If the calibration
fails (e.g., user is panning rapidly), it silently retries on next launch.

#### 2.3.2 Interpreting Results

- **Threshold detected:** `dcgRatio = thresholdIso / baseIso`, clamped to [2, 8].
  The high-ISO request uses exactly `thresholdIso` rather than `baseIso *
  DCG_RATIO`.
- **No threshold detected:** The sensor has a linear noise curve (no hardware
  DCG). `DCG_RATIO` remains at the software default (4). Bracketing still
  helps — it trades highlight headroom for shadow recovery — but there is no
  hardware gain switch to exploit.
- **Insufficient data:** Calibration retries on next launch. `dcgThresholdIso`
  remains null, and the default `DCG_RATIO = 4` is used.

---

## 3. Pipeline Mathematical Invariants

### 3.1 Raw Develop Flow

The develop pipeline converts a merged CFA (Color Filter Array) plane into
viewable sRGB pixels. The order of operations is fixed. Each step operates on
the output of the previous step. Reordering any two steps produces incorrect
output.

```
Merged CFA (uint16, Bayer-patterned)
  |
  v
1. Black subtraction
   For each pixel: value -= blackLevel[cfaColor]
   Clamp to [0, whiteLevel - blackLevel]
   Invariant: no pixel is negative after this step.
  |
  v
2. Hot pixel correction
   For each pixel: if |value - median(same-color neighbors)| > threshold * range,
     replace with median.
   Operates on black-subtracted values. The threshold is in units of full scale.
   Default threshold: 0.10 (10% of range).
  |
  v
3. Lens shading correction
   For each pixel: value *= shadingGain(x, y, cfaColor)
   shadingGain is bilinearly interpolated from the camera's lens shading map
   (STATISTICS_LENS_SHADING_CORRECTION_MAP).
   Applied per-channel (R, Gr, Gb, B have different shading profiles).
   Invariant: after this step, a uniform scene produces uniform pixel values.
  |
  v
4. White balance
   For each pixel: value *= wbGain[cfaColor]
   wbGain = [R_gain, 1.0, 1.0, B_gain] from AWB result or manual setting.
   Green channels are not scaled (they are the reference).
   Invariant: after this step, a neutral grey produces equal R, G, B values.
  |
  v
5. Demosaic (Malvar-He-Cutler gradient-corrected linear)
   For each pixel: interpolate the two missing color channels using a 5x5 kernel
   with gradient correction from the known channel.
   Input: white-balanced CFA plane.
   Output: RGB triplet per pixel.
   Invariant: a pixel's own-color channel is its measured value, not interpolated.
  |
  v
6. Color matrix (sensor RGB -> sRGB)
   [R']   [m00 m01 m02] [R]
   [G'] = [m10 m11 m12] [G]
   [B']   [m20 m21 m22] [B]
   Matrix from SENSOR_COLOR_TRANSFORM1/2, interpolated by CCT (Phase 2).
   Currently: single forward matrix per session.
  |
  v
7. Tone curve (global, hue-preserving)
   Three stages applied in order:
   a. Highlight roll-off: compress the *max channel* and scale all three by the
      same factor. This preserves hue through the compression — no orange
      highlight shift.
   b. Highlight desaturation: blend toward neutral in the top of the range.
      Controlled by desaturationStart and highlightDesaturation parameters.
   c. Contrast: S-curve applied after gamma encode. Slope at mid-grey =
      1 + 0.875 * contrast.
  |
  v
8. sRGB gamma encode
   For each channel:
     if linear <= 0.0031308: sRGB = linear * 12.92
     else: sRGB = 1.055 * linear^(1/2.4) - 0.055
   Via 4096-entry LUT for speed.
  |
  v
9. Capture sharpening
   Unsharp mask on luminance channel only. Restores acutance lost to CFA
   sampling and demosaic. This is a restoration, not an effect.
   Parameters: radius (default 1.2px), amount (default 0.4), threshold
   (default 4 code values).
  |
  v
10. Defringe (lateral CA suppression)
    Per-pixel: if a pixel is at an edge AND its chroma is an outlier among
    same-luminance neighbors, desaturate toward the local mean.
    Does not correct longitudinal CA (Phase 2 adds radial warp for that).
  |
  v
Output: 8-bit sRGB ARGB int array -> JPEG via libjpeg-turbo
```

**Future insertions (marked by phase):**

- **After step 5, before step 6 (Phase 2):** Geometric lens distortion
  correction (Brown-Conrady undistortion) and lateral CA correction (per-channel
  radial warp).
- **Replacing step 5 (Phase 3):** Sub-pixel kernel accumulation, which produces
  full colour at 2x resolution and removes the demosaic entirely for bursts that
  take that path. See §3.6, and §3.6.5 for what the chain becomes.
- **After step 7, before step 8 (Phase 4):** Bilateral-grid local tone mapping.

### 3.2 Merge Accumulation Mathematics

For a burst of N frames, one is designated the reference (lowest ISO). For each
pixel position (x, y):

```
reference value:  r = frame_ref[x, y]

For each alt frame i (i = 1..N-1):
  aligned value:  a_i = frame_i[x + dx_i, y + dy_i]    (from alignment field)
  normalized:     n_i = a_i / gainScale_i               (gain normalization)
  difference:     d_i = |n_i - r|
  noise estimate: sigma (from MAD of local neighborhood)
  weight:         w_i = exp(-(d_i / sigma)^2)           (robustness weighting)

Accumulation:
  sum_weighted = r + sum(w_i * n_i)
  sum_weights  = 1 + sum(w_i)
  result[x, y] = sum_weighted / sum_weights

Mean contribution: sum(w_i) / (N - 1)
  This is the "kept %" shown to the user.
```

**Invariants:**

- When `gainScale = 1.0` for all frames, the merge is identical to the
  pre-DCG merge. The A/A null test is the proof.
- The reference frame always contributes with weight 1.0. Even if every other
  frame is rejected (all `w_i = 0`), the output is the reference frame. A
  single-frame "merge" is never worse than not merging.
- Robustness weighting is self-normalizing: no per-scene threshold tuning
  needed. The noise estimate `sigma` adapts to the actual noise level.

### 3.3 Alignment Mathematics

#### 3.3.1 Tile Search

Hierarchical tile-based alignment, coarse to fine. **An earlier draft of this
section described a flat ±16 search; that was never what the code did.**

```
The luma proxy (2x-downsampled from the sensor) is built into a 4-level
pyramid, each level a 2x2 box downsample of the one below. Level 0 is the
proxy itself; level 3 is 1/8 of it, 1/16 of the sensor.

The proxy is divided into tiles of ~32x32 proxy pixels. The tile grid is
derived by truncating division, exactly as the merge derives it, so the two
agree about where a tile is to the right-hand edge.

For each level, coarsest first:
  1. Offsets inherited from the coarser level are doubled.
  2. For each tile, the search is seeded from the best of: the inherited
     offset, the left neighbour's solved offset, the upper neighbour's, and
     zero. This lets a tile recover when the coarse level misled it.
  3. A ±4 window around the seed is searched for the minimum SAD.
     81 evaluations per tile per level. At level 3 this covers ±32 proxy
     pixels, ±64 sensor pixels -- far beyond burst hand tremor.
  4. At level 0 only: sub-pixel refinement. Four more evaluations at the
     winner's cardinal neighbours, and a parabola through each axis's three
     costs gives the fractional offset of the minimum. Bounded to ±0.5; a
     vertex outside that means the integer search was wrong by a pixel and
     no parabola fixes it, so zero is returned rather than a clamp.

Output: AlignmentField(tilesX, tilesY, dx[], dy[], fracDx[], fracDy[])
  dx, dy      integer winners, proxy pixels
  fracDx, dy  refinement, proxy pixels, zero where not refined
```

The two merges spend the field differently. The Bayer-domain merge (§3.2)
doubles `dx`, `dy` and uses nothing else: an offset in the CFA domain must be
even or a red sample lands on a green site, so a fraction is not something that
merge can spend. The sub-pixel accumulation (§3.6) is the one consumer of
`fracDx`, `fracDy`, and exists to spend them.

The luma proxy is computed with `gainScale` normalization: `proxy_value = (raw /
gainScale)`, so brightness differences from ISO bracketing don't confuse the SAD
metric.

Native (`Align.cpp`) and Kotlin (`Aligner.kt`) make every decision identically,
including tie-breaking order, and `AlignerParityTest` holds them to zero
differing tiles and a sub-pixel difference under 1e-6.

#### 3.3.2 OIS-Seeded Search — not available on the Pixel 11 Pro

**Status: hardware-blocked on the primary target. Not built.**

Probed on device before anything was built on it (`OisProbeDeviceTest`,
commit `608b381`):

| Lens | OIS hardware | `STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES` |
|---|---|---|
| 12mm | no | `[OFF]` |
| 24mm | yes | `[OFF]` |
| 105mm | yes | `[OFF]` |

Two of the three lenses have a stabiliser; neither reports through Camera2.
`STATISTICS_OIS_SAMPLES` will never arrive on this device. The stream requests
the mode where a lens's characteristics offer it, so a device that does report
is recognised; on this phone that gate is closed on every lens.

**Two corrections to the earlier draft of this section.** It described the
search as a flat ±16 window and promised a 1089→81 saving from seeding. The
search is the pyramid in §3.3.1 and is already 81 evaluations per tile per
level; the saving does not exist. And the coarsest level's ±4 covers ±64 sensor
pixels, well beyond burst hand tremor, so the pyramid does not need a prior to
find the answer. What a seed would have bought is robustness on repeating
texture at the coarse level, not speed.

**Gyro-seeded search was considered and declined** for the same reason: the
pyramid's reach already exceeds what the IMU would tell it, and the engineering
would buy nothing the search does not have.

The remainder of this section is retained as the design a device that does
report OIS would use.

**The design, for a device that reports.** The optical stabiliser spends the
exposure measuring and counteracting the scene's motion, and Camera2 can hand
over its record of it.

**The data.** Gated on `STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES` containing
`STATISTICS_OIS_DATA_MODE_ON`. Set `STATISTICS_OIS_DATA_MODE` on the request;
read `STATISTICS_OIS_SAMPLES` from the `TotalCaptureResult` as a
`List<OisSample>`, each carrying:

| Field | Meaning |
|---|---|
| `getTimestamp()` | Nanoseconds, on the same clock as `SENSOR_TIMESTAMP` |
| `getXshift()` | Lens shift, active-array pixels |
| `getYshift()` | Lens shift, active-array pixels |

Several samples arrive per frame, spread across the exposure window.

**Deriving a seed.**

```
For each frame f:
  samples(f) = OIS samples whose timestamp lies within f's exposure window
  ois(f)     = time-weighted mean of (xShift, yShift) over samples(f)

For an alt frame a against reference r:
  seed(a) = k * (ois(a) - ois(r))     scaled to proxy resolution
```

The difference matters, not the absolute position: a constant OIS offset shared
by both frames displaces neither relative to the other. The result is scaled by
the proxy's downsample factor, since the search runs on the 2x-downsampled luma
proxy rather than at sensor resolution.

**On the sign of `k`.** It is written as a constant here because its sign is not
something to assume. The OIS shift is a *lens* displacement applied to cancel
scene motion, so whether the corresponding image displacement runs with it or
against it depends on the optical path, and vendors have been known to differ in
convention. Determine it empirically: run a burst with OIS data captured
alongside the existing blind search, and correlate the measured tile
displacements against the OIS deltas. If the correlation is strongly negative,
`k` is negative. A device test that asserts the correlation exceeds a threshold
in the expected direction is what keeps a later firmware change from silently
inverting it.

**What it buys.** With a seed accurate to a pixel or two, the search window
shrinks from ±16 to ±4: 81 evaluations per tile instead of 1089, a 13x
reduction in the dominant cost of alignment. The sub-pixel parabola refinement
is unchanged and still runs on the 3×3 neighbourhood of the minimum.

The accuracy matters as much as the speed. A ±16 blind search can lock onto a
repeating texture at the wrong period; seeded to within a couple of pixels, that
ambiguity mostly disappears. This is why the accumulation in §3.6 depends on
this: it places samples at sub-pixel positions and is only as good as the
displacements it is given.

**Fallbacks.** No OIS on the lens, the mode unsupported, or an empty sample list
for a frame — each falls back to the current blind ±16 search for that frame.
The seed is an optimisation, never a requirement, and a frame without one is
aligned exactly as it is today.

**Guard.** If the seeded search finds its minimum at the edge of the ±4 window,
the seed was wrong and the tile is re-searched at ±16. Without this, a bad seed
silently produces a confidently wrong alignment, which is worse than a slow one.

### 3.4 Super-Resolution Stitching Mathematics

#### 3.4.1 Planar Homography

Each tile pair (tile A overlapping tile B) is related by a projective transform:

```
[x']     [h00 h01 h02] [x]
[y'] = s [h10 h11 h12] [y]
[ 1]     [h20 h21  1 ] [1]

where s = h20*x + h21*y + 1 (projective normalization)
```

The 8 free parameters (h00..h21) are estimated from >= 4 point correspondences
found by feature matching (FeatureMatcher) in overlapping regions.

Estimation: Direct Linear Transform (DLT) with RANSAC for outlier rejection.
4-point minimal solver, 500 iterations, inlier threshold = 3 pixels.

#### 3.4.2 Bundle Adjustment

After individual pairwise homographies are estimated:

```
Global cost function:
  E = sum over all (i,j) tile pairs with overlap:
    sum over all inlier correspondences (p_k, q_k):
      ||H_i * p_k - H_j * q_k||^2

Variables: all H_i (8 params each), with H_0 = Identity (anchor).

Optimization: Levenberg-Marquardt.
  Jacobian: sparse (each correspondence involves exactly two tiles).
  Convergence: typically 5-10 iterations.
  No external solver: the normal equations are formed and solved directly.
```

#### 3.4.3 Multi-Band Laplacian Blending

For each pair of overlapping tiles in the composite:

```
1. Build Gaussian pyramid of each tile (4 levels).
2. Build Laplacian pyramid (difference between consecutive Gaussian levels).
3. Build a binary mask at the seam (which tile "owns" which pixel, determined
   by Voronoi of tile centers).
4. Blend each Laplacian level using a Gaussian-blurred version of the mask
   at that level's resolution.
5. Reconstruct from the blended Laplacian pyramid.
```

This blends low-frequency differences (exposure, color temperature) over wide
regions while preserving high-frequency detail (texture, edges) at the seam.
The result has no visible seam even when tiles differ in exposure by 1+ stop.

### 3.5 Bilateral Grid Local Tone Mapping (Phase 4)

```
Input: linear RGB image after color matrix, before global tone curve.

1. Compute luminance: L = 0.2126*R + 0.7152*G + 0.0722*B
2. Build 3D bilateral grid:
   - Spatial dimensions: image downsampled by 16x in each axis.
   - Range dimension: 16 bins spanning [0, max_luminance].
   - Each cell stores: (sum_of_log_L, count).
3. Splat: for each pixel, accumulate log(L) into the grid cell indexed by
   (x/16, y/16, L_bin).
4. Blur: 3D Gaussian blur on the grid (sigma_spatial=1, sigma_range=1).
5. Slice: for each pixel, trilinearly interpolate the grid at (x/16, y/16, L)
   to get the local average log-luminance.
6. Detail layer: log(L) - local_average = local contrast (preserved).
7. Compressed base: global tone curve applied to local_average.
8. Reconstruct: compressed_base + detail layer -> output luminance.
9. Apply to RGB: scale each channel by (output_L / input_L).

Result: global dynamic range is compressed, local contrast is preserved,
no halos (the grid's spatial binning prevents them).
```

---

### 3.6 Sub-Pixel Kernel Accumulation (Phase 3)

**The measurement demosaicing throws away.** A Bayer sensor measures one colour
per photosite. Demosaicing invents the other two by interpolating neighbours —
§3.1 step 5 does it as well as a linear method can, but two thirds of every
pixel in the output is still a guess.

A burst does not have to guess. Between frames the sensor moves a fraction of a
photosite, so a site that measured green in one frame sits over a slightly
different part of the scene in the next, and across the burst red, green and
blue samples land all over the scene at sub-pixel spacing. **The missing colours
were measured.** Demosaicing first and merging second discards that: it
interpolates each frame independently, then averages the interpolations.

This inverts the order. Raw CFA samples are accumulated directly onto a
double-resolution canvas at their measured sub-pixel positions, and the picture
that falls out has real colour at every cell — no interpolation step at all.

#### 3.6.1 The Accumulation

```
Canvas: 2x sensor resolution in each axis.
Per cell, per colour channel c: a weighted sum and a weight.
    acc[c], wsum[c]

For each frame f, for each CFA sample s at sensor position (sx, sy):
    c        = colour of the CFA site (0 red, 1 green, 2 blue)
    (dx, dy) = per-pixel displacement, bilinearly interpolated
               from f's AlignmentField
    (u, v)   = 2 * (sx - dx, sy - dy)        canvas coordinates, fractional

    For each canvas cell (i, j) within the kernel radius of (u, v):
        d  = distance from (i, j) to (u, v)
        k  = kernel(d)
        r  = robustness weight of s          as in 3.2
        w  = k * r

        acc[c] (i,j)  += w * (value(s) / gainScale(f))
        wsum[c](i,j)  += w

Resolve, per cell, per channel:
    out[c] = acc[c] / wsum[c]       where wsum[c] > threshold
```

Three things carry over from §3.2 unchanged: gain normalisation, so a DCG burst
accumulates on one scale; robustness weighting, so a sample that disagrees with
the reference contributes little; and the reference frame's guaranteed
contribution, so a cell is never composed entirely of rejected samples.

**The robustness comparison had to change, and the tests found it.** §3.2
compares an alternate sample to the reference at the *integer* alignment, which
it can do because it only ever places at the integer. This places at the
fraction, and two things follow:

1. **Compare at the fraction.** A half-pixel shift at an edge means the
   alternate legitimately saw the edge where the reference's integer-aligned
   site did not. Comparing them rejects precisely the sample carrying the
   sub-pixel information. So the reference is interpolated bilinearly on the
   sample's *own colour lattice* — same-colour sites form a lattice of period
   two in each axis — at the fractional position, and asked what it expects
   there.

2. **Widen the tolerance by what the reference cannot know.** Even at the
   fraction, a bilinear ramp between two reference sites four canvas cells
   apart cannot represent a step, and mid-edge it is wrong by half the step.
   On a noiseless synthetic scene this rejected 26% of samples and left 7% of
   interior cells with nothing but rejected weight. The tolerance is therefore:

   ```
   tolerance² = noise²(level) + uncertainty²
   uncertainty = ½ × max total variation across the lattice cell
   ```

   Where the reference is flat the second term vanishes and this is §3.2's
   test. Where it has an edge, the sample that reads the edge is kept — which
   is the whole point. The noise estimate itself is taken only from cells
   whose uncertainty is below a flatness threshold (20 counts on a 10-bit
   range), so interpolation error does not loosen it for the whole frame.

   Measured: acceptance went from 74% to 99%, interior holes from 7% to zero,
   on the same scene.

**Detail finer than the reference's per-colour Nyquist is attenuated, not
resolved.** Bars at 1.5 sensor pixels are below what any single frame's red or
blue lattice can represent, so the reference cannot witness them and the
robustness weight reads the alternates' true detail as disagreement. It
survives — tested at >50% contrast — but at reduced contrast and with fill
where every sample was rejected. That is a limit of comparing against a
bandwidth-limited reference, is documented as such, and is tested separately
from placement. Detail *at* the sensor's Nyquist — two sensor pixels, the
canonical super-resolution target — resolves at >75% of true contrast.

#### 3.6.2 The Kernel

Baseline is a radially symmetric Gaussian, σ ≈ 0.5 canvas cells, truncated at
2σ. Simple, has no parameters to tune per scene, and cannot introduce structure
that was not measured.

The refinement, if the baseline proves too soft: steer the kernel by the local
gradient — narrow across an edge, wide along it — which resolves edges more
sharply without the ringing an isotropic sharpen would add. This is a
*refinement*, gated on the baseline shipping and being measurably too soft. An
anisotropic kernel has failure modes on fine repeating texture that an isotropic
one does not, and it is not worth them until the simple version is known to be
the limiting factor.

#### 3.6.3 Coverage and Holes

A cell can end a burst with no samples of some channel — insufficient sub-pixel
diversity, or every candidate sample rejected as an outlier. `wsum[c]` below
threshold marks it.

Holes are filled from the nearest *measured* cell of that channel, searched in
rings out to three cells, reading from a snapshot so a fill never borrows
another fill. This is interpolation, and it is the one place in this path where
a value is inferred rather than measured — so it is confined to cells the burst
genuinely did not reach, and the hole fraction is recorded, separately for the
whole canvas and for its interior (a border of four cells excluded, since a
shifted frame's samples fall off the edge and that is geometry, not diversity).

**What the geometry guarantees, measured.** Green is sampled every sensor
pixel — every two canvas cells — and half-pixel shifts reach both, so green
covers. Red and blue are sampled every *two* sensor pixels — every four canvas
cells — and half-pixel shifts reach only two of the four:

| Burst | Interior holes | R / G / B RMS vs kernel-blurred truth |
|---|---|---|
| Quad shift (0, ½ in each axis; 4 frames) | 16.7% = ¼ of R + 0 of G + ¼ of B, over 3 | 42 / 22 / 43 |
| Full lattice (0, ½, 1, 1½; 16 frames) | 0.0% | 6.2 / 2.2 / 1.2 |

A real burst has eight to thirty frames at whatever offsets a hand produces and
covers red and blue statistically rather than by construction. The hole
fraction is what says how well it did.

**A high hole fraction means the burst lacked sub-pixel diversity**, which is
the tripod case §2.3.6 of the PRD detects. On the Pixel 11 Pro the dither that
would have supplied diversity is infeasible (see §2.3.6's measured actuator
timing), so the hole fraction is the signal to decline this path and shoot the
ordinary merge.

#### 3.6.4 Memory

This is the constraint that shapes the implementation.

A 12 MP sensor gives a 48 M-cell canvas. Carrying three channels of accumulator
and three of weight as `float` is 24 bytes per cell — **1.15 GB**, against a
`MemoryBudget` that assumes a 256 MB heap on this device. Not viable whole.

Resolved the way `MosaicCanvas` already resolves it: process in horizontal
strips, mmap-backed, one strip resident at a time. A 256-row strip of a
4080-wide sensor at 2x is 8160 × 512 cells ≈ 100 MB, which fits. The kernel
radius is 2σ ≈ 1 cell, so strips need only a one-cell halo of overlap for the
kernel to reach across the boundary — a hundredth of the strip.

`float16` accumulators would halve it again and are worth measuring, but only
after correctness: a burst of 32 frames accumulating into a 10-bit mantissa is
close enough to the precision floor that it needs a parity test against the
`float32` version rather than an assumption.

#### 3.6.5 What This Does to the Develop Chain

For bursts taking this path, §3.1 changes:

```
Merged CFA -> black -> hot pixel -> shading -> white balance
  -> DEMOSAIC                                    <-- removed
  -> colour matrix -> tone curve -> ...
```

becomes

```
Aligned raw samples -> black -> hot pixel -> shading -> white balance
  -> SUB-PIXEL ACCUMULATION (this section)       <-- replaces demosaic,
                                                     and outputs at 2x
  -> colour matrix -> tone curve -> ...
```

The preparatory passes move ahead of accumulation because they are per-sample
corrections and must be applied to a sample before it is placed. Everything
downstream of the colour matrix is unchanged and simply receives a larger,
full-colour buffer.

**This does not replace the existing merge.** A single frame, a burst with no
measurable sub-pixel diversity that dither could not supply, or a device short
of memory all still take the §3.2 path and demosaic normally. The two coexist,
and which one ran is recorded in the capture readout — a 2x output is a
different claim about the photograph and should say so.

#### 3.6.6 Verification

| Property | Test |
|---|---|
| A/A null | A burst of one frame, accumulated, must match that frame demosaiced to within the kernel's known softening. It cannot invent detail from a single sample set. |
| Synthetic ground truth | Render a known high-resolution target, sample it through a simulated Bayer grid at known sub-pixel offsets, accumulate, and compare against the original. The recovered detail is measurable rather than a matter of opinion. |
| Native/Kotlin parity | As `MergeParityTest` does for §3.2 — a reference implementation in Kotlin, bit-comparable to the NEON path within the `-ffast-math` bound of 1e-5. |
| Hole fraction | Recorded per capture and asserted near zero on a dithered tripod burst; a rise is the signal that dither has stopped working. |
| No-diversity guard | A burst captured rigidly with dither disabled must decline this path rather than produce a 2x output built from interpolation. |

---

## Appendix: File Inventory by Subsystem

### Pipeline (app/src/main/java/dev/multiframe/camera/pipeline/)

| Subsystem | Files |
|---|---|
| **Capture & ZSL** | `ZslRawStream.kt`, `ZslCapture.kt`, `ZslPolicy.kt`, `RawRing.kt`, `BurstBuffer.kt`, `RawCapture.kt`, `RawBurstCapture.kt` |
| **Merge** | `BayerMerger.kt`, `BayerFrame.kt`, `Merger.kt`, `NativeMerge.kt`, `Aligner.kt`, `SubPixelAccumulator.kt`, `NativeSubPixel.kt` |
| **Develop** | `RawDeveloper.kt`, `Demosaic.kt`, `ToneCurve.kt`, `ColorScience.kt`, `ColorProfile.kt`, `LensShading.kt`, `Sharpen.kt`, `Defringe.kt`, `HotPixels.kt`, `ToneAblation.kt` |
| **Output** | `ImageSaver.kt`, `NativeJpeg.kt`, `ExifWriter.kt`, `DngMetadata.kt` |
| **Exposure** | `ExposureStrategy.kt`, `CaptureMode.kt` |
| **Camera** | `CameraCapabilities.kt`, `LensCatalog.kt`, `LensProfile.kt`, `ManualSettings.kt`, `TouchFocus.kt`, `FocusPeaking.kt`, `OrientationTracker.kt` |
| **DCG calibration** | `DcgCalibration.kt`, `DcgProfiler.kt`, `DcgCalibrationStore.kt` |
| **Mosaic** | `MosaicSession.kt`, `MosaicCapture.kt`, `MosaicPlanner.kt`, `MosaicAssembler.kt`, `MosaicCanvas.kt`, `MosaicRefiner.kt` |
| **Memory** | `MemoryBudget.kt`, `MemoryPressure.kt` |
| **Settings** | `AppSettings.kt`, `CaptureReadout.kt`, `RecentCapture.kt`, `CaptureLog.kt` |
| **GPU** | `GpuCrossing.kt` (benchmark only) |
| **Geometry** | `Homography.kt`, `FeatureMatcher.kt` |
| **Sensor** | `LevelGauge.kt`, `YuvFrame.kt`, `TripodDetector.kt`, `DitherController.kt`, `DitherPulse.kt` |

### Native C++ (app/src/main/cpp/)

| File | Purpose |
|---|---|
| `bayer_merge.cpp` | NEON-vectorized merge accumulation, noise estimation, shading correction, prepass. |
| `SubPixel.cpp` | Sub-pixel kernel accumulation onto a 2x canvas, strip-major over an mmap output (§3.6). Held to `SubPixelAccumulator.kt` within one 16-bit count. |
| `Align.cpp` | Native 4-level pyramid tile alignment with parabolic sub-pixel refinement at the finest level (§3.3.1). Held to `Aligner.kt` at zero differing tiles. |
| `RingBuffer.cpp` / `.h` | mmap-backed AHardwareBuffer ring buffer |
| `Mosaic.cpp` / `.h` | Native mosaic tile compositing |
| `Rotate.cpp` | NEON-accelerated image rotation |
| `Jpeg.cpp` | libjpeg-turbo JNI bridge (parallel striped encoding) |
| `GpuCrossing.cpp` | Vulkan crossing cost benchmark (not production) |

### UI (app/src/main/java/dev/multiframe/camera/ui/)

| File | Purpose |
|---|---|
| `Theme.kt` | `Ink` palette, `Type` scale, `Haptics` vocabulary, `MultiframeTheme` |
| `Gauges.kt` | `GaugeState` holder and the live readouts: exposure, histogram, focus, zoom |
| `ControlBar.kt` | Control data model: `ControlSpec`, `ControlBarState`, `Detents`, mode/action builders |
| `ControlChips.kt` | Composable rendering of chips: `ControlRow`, `ActionStrip`, `ControlChip` |
| `ControlsPanel.kt` | PRO panel: manual exposure, focus, WB, toggles, sliders |
| `Gallery.kt` | Gallery grid, review overlay with pixel-level zoom, delete confirmation |
| `Guides.kt` | Composition guides (rule of thirds, golden ratio, etc.) |
| `AboutSheet.kt` | About/info bottom sheet |

### Build Configuration

| Parameter | Value |
|---|---|
| `compileSdk` | 37 |
| `minSdk` | 33 (Android 13) |
| `targetSdk` | 36 |
| `ndkVersion` | 28.2.13676358 |
| ABI | arm64-v8a only |
| C++ standard | C++20 |
| Kotlin | 2.4.10 |
| AGP | 9.3.2 |
| Compose BOM | 2026.08.00 |
| CameraX | 1.6.1 |
| libjpeg-turbo | vendored (ARM64 NEON SIMD only) |
