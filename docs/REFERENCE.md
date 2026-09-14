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
| `Panel` | `#0A0C0E` | 94% | A panel over the picture, where one is drawn. Opaque enough to read controls |
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
| `Peaking` | `#3DF2FF` | Focus peaking: the edges that are resolving, in a hue no scene has. Appears only while manual focus is engaged |

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
| `Confirm` | `#5BC87A` | 100% | Success states outside the viewfinder (Super Res tile captured) | Never on the viewfinder except during active Super Res guidance |
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
   operation. They appear only in: gallery delete confirmation, Super Res tile
   guidance (during an active capture only), and error dialogs.

5. The three exception tokens -- `Level`, `Clipping`, `Peaking` -- are the
   only chromatic marks permitted over the photograph, and each is a
   *transient state about the photograph*: it appears when the thing is
   true and vanishes when it stops being true. `Peaking` is drawn in the
   preview's bounds and orientation, from the sensor's own luma, while
   manual focus is engaged and at no other time.

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
| Mode toggle | GUARD, 2X, LOCAL TONE, HISTOGRAM, DNG, SUPER RES 2×2/3×3 changed | `Haptics.segment` | Detent feel, like a mechanical switch |
| Cycle value | FRAMES, TIMER, GUIDES stepped | `Haptics.segment` | Same detent feel for any discrete step |
| Mode selected | AUTO, NIGHT, ACTION or SUPER RES chosen in the carousel | `Haptics.segment` | A dial detent; selecting SUPER RES also switches the lens, which has the same detent |
| Super Res stopped | STOP tapped, or the back gesture while running | `Haptics.segment` | Acknowledging the request; the stop itself lands a moment later and is not felt |
| Slider at detent | ISO at round number (100, 200, ...), EV at 0 | `Haptics.tick` | Subtle stop point, not every pixel of drag |
| Lens switch | New lens selected in strip | `Haptics.segment` | Camera is physically switching |
| Super Res anchored | First frame taken; the dots appear | `Haptics.segment` | The capture has begun; same detent as the lens switch that preceded it |
| Super Res node taken | Reticle has held still on a dot through the stability gate; the burst is in hand and the node is done | `Haptics.confirm` | "Got it" — eyes are on the subject. Fires *after* the burst is snapshotted from the ring, never before: the frames a haptic could blur are already in hand. This is the photograph; nothing afterwards can un-take it |
| Super Res tile placed | The node's tile is on the canvas, by its pixels or by the attitude | `Haptics.tick` | Light: the canvas caught up; which tier placed it is the log's business |
| Super Res tile rejected | The burst could not become a tile at all: the ring was empty, the develop failed, the canvas would not take it | `Haptics.reject` | "Try again" — also eyes on subject; the dot flashes red for a beat. Never for a registration that found nothing: that tile is placed by the attitude |
| Mosaic pan too fast | Frame centre moved most of a tile between polls | `Haptics.reject` | Rate-limited to one per 800 ms; a fast pan is a blurred frame everywhere along it |
| Mosaic complete | Every node captured | `Haptics.confirm` | The mosaic has finished itself |
| Error action | Tapping a disabled control, invalid state | `Haptics.reject` | Something went wrong |
| Settings open/close | The gear over the picture; DONE or the back gesture | (none) opening, `Haptics.segment` closing from DONE | Opening a screen is its own feedback; DONE is a control and gets the detent |
| PRO drawer open/close | PRO beside the shutter; the back gesture | (none) | The drawer's slide is sufficient feedback |
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
| Telephoto has no RAW | `LensProfile.supportsRaw == false` for telephoto | `MosaicPlanner.captureLens` falls to the longest raw lens; with none, SUPER RES is not in the carousel. |
| No attitude sensor | `MotionSensor.hasAttitude == false` | SUPER RES is not in the carousel: the guidance cannot run. |
| No OIS on telephoto | `LensProfile.hasOis == false` | Allow Super Res; the stability gate and the shutter floor are what hold the tile sharp. |
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
     winner's cardinal neighbours, and an equiangular fit through each
     axis's three costs gives the fractional offset of the minimum:
         t = (before - after) / (2 * (max(before, after) - at))
     Bounded to ±0.5; a vertex outside that means the integer search was
     wrong by a pixel and no fit fixes it, so zero is returned rather than
     a clamp.

Output: AlignmentField(tilesX, tilesY, dx[], dy[], fracDx[], fracDy[])
  dx, dy      integer winners, proxy pixels
  fracDx, dy  refinement, proxy pixels, zero where not refined
```

**Why the equiangular fit and not the parabola.** The first version fitted a
parabola through the three costs -- the textbook refinement, and correct for a
cost that is quadratic near its minimum. A sum of absolute differences is not:
shift a textured image by t and its SAD against the unshifted one grows as |t|.
Three samples of a V do not lie on the parabola that has its vertex at the V's,
and the parabola through them puts its vertex at `t / (2 (1 - t))` -- a bias
toward zero of nearly half. Measured before it was reasoned, on the real proxy
and search at the sensor's full width (`SubPixelBiasDeviceTest`, true shift in
sensor pixels against the recovered):

| True | Parabola | Equiangular |
|---|---|---|
| 0.30 | 0.12 | 0.21 |
| 0.50 | 0.26 | 0.41 |
| 0.70 | 0.43 | 0.60 |
| 1.00 | 0.81 | 0.90 |

The equiangular fit is the vertex of the V through the three points and is
exact for one. Its residual -- a slope of one with a tenth of a pixel of
offset -- is the other axis's unrefined fraction leaking in through diagonal
texture, which a separable three-point refinement cannot see. This matters more
for the 2x canvas (§3.6) than for the merge: the merge blends samples over a
tile and a quarter-pixel error softens nothing visible, but the canvas *places*
each sample by this fraction, and a quarter of a sensor pixel is half a canvas
cell, the kernel's whole sigma, on every sample of every frame.

A tile whose content is a pure grating at the sensor's Nyquist cannot be
refined at all: on the 2x2-binned proxy that grating is at the proxy's Nyquist,
where a shift changes its contrast rather than its phase. Samples such a tile
places by a wrong offset disagree with the reference and are rejected, and the
canvas there is the reference's. That is the honest answer, and the hole fill
is what makes it a picture.

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

**As built** (`Mosaic.cpp`, `MosaicCanvas.addTile(tile, transform, feathers)`).
Burt and Adelson's construction, in the running-mean form the canvas already
kept, so nothing new can overflow and the cost is an eighth again of the canvas.

A tile's Laplacian band k is `G_k - up(G_{k+1})`: its Gaussian level less the
next coarser one upsampled. Blending that band across tiles with a mask `M_k`
of feather `f_k` gives

```
sum_t (G_k(t) - up(G_{k+1}(t))) M_k(t) / sum_t M_k(t)  =  A_k - up(B_{k+1})

A_k      running mean of G_k     under the level's own feather  f_k
B_{k+1}  running mean of G_{k+1} under the finer level's feather f_k
```

approximating that upsampling commutes with the weighted mean, which is exact
wherever the mask is locally flat. So each coarser level keeps two canvases,
and the finished pixel is

```
out = A_0 - up(B_1) + up(A_1 - up(B_2) + up(A_2))
```

| Level | Scale | Canvases | Mask |
|---|---|---|---|
| 0 | 1 | the canvas as before | ramp 24 px × hand-off |
| 1 | 1/4 | `wide_[0]` (M_1), `narrow_[0]` (M_0) | M_1: ramp 160 px |
| 2 | 1/16 | `wide_[1]` (M_2), `narrow_[1]` (M_1) | M_2: ramp 600 px |

The pyramid steps by four, not two: the corrections are for exposure and
colour, which are very low frequency, and two levels at a quarter and a
sixteenth reach them for an eighth of the canvas rather than a third. The
tile's levels are 4×4 box means, read bilinearly at quarter scale.

**The detail band's mask hands off; it does not feather.** This was the
finding of the seam test's first run. A ramp measured from each tile's own
edge reaches one a feather's width in, and from there to the far side of the
overlap both tiles weigh the same -- so the overlap's interior is an average
of two textures a registration error apart, a double image, whatever the
ramp's width. The bands changed nothing because the detail band was averaging
exactly as the single feather had. The spec's Voronoi seam is what was
missing: the tile whose centre is nearer owns the pixel. In the running-mean
form that is a weight that rises steeply with depth into the tile,
`ramp² × (edge / halfTile)^12`, so the deeper tile outweighs the shallower by
a large ratio everywhere but a narrow zone about the equidistant line -- a
soft Voronoi, a fifteenth of a 35% overlap wide. The coarser bands keep plain
ramps, and each level's `narrow_` canvas carries the finer level's mask,
hand-off included, since that is the blend it corrects.

**Which forced the weight into a logarithm.** The canvas stored its weight as
fixed point at 512 per unit; a twelfth-power hand-off at the far side of an
overlap is a millionth, which fixed point stored as zero -- the pixel read as
uncovered and the next tile took it as a first sample, and the seam step went
to 26 codes. The weight field is now sixteen bits of exponent, `2^((code -
32768) / 2048)`, sixteen octaves either side of one at a thirtieth of a
percent, with code 0 uncovered. The running mean uses only the ratio of
weights and is unaffected.

**The null.** With one tile every canvas at a level holds that tile's own
level wherever it holds anything, whatever its mask, so `A_k = B_k` and the
corrections cancel: the three-band output is the one-band output to the byte.
Composition reads each level's pair through the *same* bilinear taps -- only
taps both canvases hold -- because a tap the wide mask reached and the narrow
one did not would put a difference into the correction that is about the
taps, not the tiles; the first version read them separately and 23,000 pixels
of a one-tile canvas differed. `MosaicBlendDeviceTest.oneTileIsTheSameToTheByteInOneBandAndThree`
asserts zero before the seam is measured.

**The seam, measured** (two 480×320 tiles of one textured scene, the right one
a quarter stop brighter and placed a pixel off; masks scaled to the tile, 12 /
60 / 160):

| | Step across the seam (codes per 8 columns) | Texture rms in the overlap (codes) |
|---|---|---|
| Right tile alone | — | 10.13 |
| One band, narrow ramp | 5.90 | 8.41 |
| One band, wide ramp | 2.12 | 8.51 |
| Three bands | **2.13** | **8.86** |

The bands spread the step as the wide ramp does and keep more of the texture
than either ramp -- the measurement window straddles the equidistant line, so
a third of it is the hand-off zone where the two tiles still mix; away from
that line the detail is one tile's alone.

#### 3.4.4 The Stability Gate and the Attitude Guidance

**As built** (`StabilityGate.kt`, `MotionSensor.kt`, `ImuGuidance.kt`,
`MosaicGuide.kt`, `MosaicCapture.kt`, `SuperResMachine.kt`), after two field
tests: the first fired every burst while the hand was still moving; the
second, with a gate, found a hand at 105mm never completes a hold at the
gate's first numbers, and found the dots swimming because the tracker that
placed them was registering seven-degree frames against each other.

**Guidance is inertial; placement is visual.** The dots -- where each node of
the grid falls in the current frame, and the outline of the canvas -- come
from the phone's attitude and nothing else. `MotionSensor` reads the game
rotation vector (`TYPE_GAME_ROTATION_VECTOR`, falling back to
`TYPE_ROTATION_VECTOR`) at the platform's fastest rate on its own thread and
keeps the last half second of rotation matrices `R` (device axes → world).
At the anchor frame, `ImuGuidance.anchor(R₀)` fixes every node as a
direction in the world: a node at sensor-pixel offset `(sx, sy)` from the
anchor frame's centre is, in display axes, `(dx, dy)`, and its device-frame
direction is `normalise(dx, −dy, −f)` with `f = pixelsPerRadian` -- x right,
y up, the rear camera down −z. Its world direction is `R₀ · v`. In the
current frame it is `R_nowᵀ · R₀ · v`, projected back as display pixels
`(f · x / −z, −f · y / −z)` and turned into sensor fractions through
`TouchFocus.toSensorFraction`. The reticle's place on the canvas is the same
arithmetic on the optical axis `(0, 0, −1)`. Roll is a rotation like any
other and costs nothing. The loop runs at 33 ms with no pixel read at all.

**The targets are static.** A node is a direction in the world, fixed at
the anchor, and nothing afterwards moves it: not a lock, not a registration.
For a pure pan of `Δyaw` the displacement on the sensor is exactly
`f · tan(Δyaw)`, and for a tilt `f · tan(Δpitch)` -- the general rotation
above reduces to that, and `ImuGuidanceTest` holds it to a twentieth of a
pixel at one, three and six degrees. Two things the third field test found
and removed: a *magnet* -- the locking node kept its lock to 1.5 radii and
its dot was drawn at the reticle -- which made a target that jumped toward
the reticle as it was approached and away as the lock dropped, a target to
chase; and a *drift correction* -- every placed tile's registration residual
was added to every dot -- which made every dot jump at every tile. The game
rotation vector's yaw drift over a capture is a fraction of a degree, a few
percent of a tile, well inside the thirty percent overlap, so the dots are
left where the world put them and the drift is logged, not applied.

The frame is paired with the attitude *at its exposure* --
`SENSOR_TIMESTAMP + exposure/2` against the attitude history -- where the
camera stamps frames on the sensors' clock (`SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`).
`TouchFocus.toSensorFraction` is the inverse of `toDisplayPoint` and is
tested as such.

**A node is never refused for what its pixels say.** The third field test's
fix refused a tile whose registration disagreed with the attitude; the
fourth, in the evening on the telephoto, found a dark frame registers
against a dark anchor not at all, and every node was refused in a loop. So
the node is *done* the moment its burst is in the ring's hand
(`MosaicEvent.Locked`: the checkmark, the `CONFIRM` haptic, the guidance
moving on), and the burst is tagged with the attitude at its reference
frame's exposure. Making the tile is a separate job on a separate thread
(`MosaicCapture.placementDispatcher`), one tile at a time in the order taken,
while the guidance thread keeps the dots moving; the ring locks one burst at
a time, so the next burst waits for the last to be merged (a fraction of a
second; the hand is still). Placement has two tiers:

1. **Registration**, against the anchor and the neighbouring tiles -- exact
   when it works. Believed when it finds at least `MIN_VISUAL_INLIERS` (10)
   inliers, puts the tile's centre within `MAX_ATTITUDE_DISAGREEMENT` (¼
   tile) of where the attitude says the phone pointed, and fits the tile
   within `MAX_SCALE_ERROR` (⅙) of a tile's size.
2. **The attitude**, otherwise: `ImuGuidance.tileTransform` -- the tile is a
   rectangle of directions in the device frame, each carried to the anchor's
   frame by `R_anchorᵀ · R_now` and met with the canvas plane. A rotation
   between two views of a plane is a homography exactly, so the four corners
   fit it exactly (the centre and edge midpoints are added for conditioning);
   at 13.6° off axis the tile is a keystone 6% wider than at the centre,
   which `ImuGuidanceTest` holds to 1%. An attitude placement is a few
   percent of a tile from exact -- inside the thirty percent overlap and
   under the blend -- and is recorded with zero inliers so later
   registrations weigh it accordingly.

Only a tile that cannot be *made* -- the ring empty, the develop failed, the
canvas refusing it -- is rejected and its node put back to pending
(`MosaicEvent.Rejected`); the worker hands that to the guidance thread, which
owns the guide. `MosaicEvent.Placed(node, byAttitude)` says which tier placed
each tile, for the log and the tick.

**The grid is angular, at thirty percent overlap.** Nodes are
`(1 − overlap)` of the field of view apart --

```
step_yaw   = FOV_h · 0.70        step_pitch = FOV_v · 0.70
node_k     = f · tan(k · step)   canvas half-width = f · tan(θ_max + FOV_h/2) · 1.05
```

-- with `f = (tileWidth/2) / tan(FOV_h/2)` in tile pixels and `FOV_v` from
the sensor's own aspect (`MosaicPlanner.gridGeometry`). The first version
stepped 0.65 of a tile in pixels and sized the canvas as if a tile at an
angle were still a tile's width on the plane; on a 3×3 that came out four
percent short and clipped the outer tiles' far edges. A grid that would reach
past seventy degrees of half-angle is refused as not a picture.

**The write has no hole in it.** Nine hand-held tiles are nine slightly
turned rectangles, and the box around their union has corners nothing
reached -- which JPEG, having no alpha, paints black. `MosaicCanvas` now
writes the largest axis-aligned rectangle every pixel of which is covered
(`CoveredInterior`, the maximal-rectangle histogram pass, one visit per
pixel), so a complete mosaic is the picture inside the union and a partial
one is the largest whole rectangle it contains.
`MosaicCanvasTest.theWrittenImageIsTheLargestRectangleWithNoHoleInIt` lays
two tiles as an L and reads no black pixel back.

**Still, in the sensor's numbers.** A frame is blurred by how far the image
moves during its exposure: through `omega * t` radians at angular speed
`omega` over exposure `t`, times the focal length in pixels
`pixelsPerRadian = f_mm * (sensorWidthPx / sensorWidthMm)`. The gate's ceiling
is the speed that moves the image two pixels:

```
omega_max = 2 px / (t * pixelsPerRadian)      clamped to [0.105, 0.21] rad/s
```

The floor is 6°/s, from 2°/s: the second field test's hand at 105mm peaked
at three to five degrees a second poll to poll and never completed a hold;
two was a tripod's number. Six admits the hand and still refuses the pan,
which is ten to twenty. Stated honestly, 6°/s at 1/250 s on the telephoto is
five sensor pixels of blur -- but that is the peak of a single gyroscope
sample in a 33 ms poll, not the exposure's mean, and the tile lands on the
canvas at half to two-thirds scale. The cap is 12°/s, where a very short
exposure would otherwise accept a wobble the merge's alignment has to chase.

**The shutter floor, and how it gives way in the dark.** What keeps 6°/s at
five pixels rather than forty is the exposure. Auto exposure on a dim scene
chooses 1/30 s without regard to the lens. When the mosaic locks the stream
(`ZslRawStream.lockForSweep`) and the converged exposure is longer than the
lens's floor, the stream is pinned at the floor with the ISO scaled to keep
the light:

```
t_floor(eq) = 4 ms · 105 / eq       clamped to [2 ms, 16.7 ms]   (1/250 s at 105mm)
ISO' = ISO · t / t_floor            clamped to the sensor's range
```

But a floor that starves the sensor guarantees a black frame: the fourth
field test's telephoto at 1/250 s in the evening asked for ISO 13,000 of a
sensor that stops at 6,000, and saw nothing. So where keeping the light at
the floor would need more than `LOW_LIGHT_ISO` (3200), and the hand is still
-- the gyroscope's peak over the last half second under
`LOW_LIGHT_ANGULAR_SPEED` (4°/s) at the moment of the lock -- the floor
*stretches*: to the exposure that brings the ISO back to 3200, no more than
`LOW_LIGHT_STRETCH` (4×) the floor, never past `LOW_LIGHT_MAX_NS` (1/60 s),
and never longer than what auto exposure had chosen. A stretched shutter is
paid for: the gate's ceiling for every node of that capture tightens to
4°/s. A moving hand gets the floor and the ceiling and the dark frame -- and
the attitude places it. (`StabilityGate.sweepExposure`; `StabilityGateTest`
walks the evening case, the slightly dark case and the cap.) A manual
exposure is the photographer's and is left alone. Dual conversion gain is
suspended for the lock's duration -- the mosaic's merge weighs every frame
alike -- and restored on unlock.

**Two measures, both required, every poll.** The gyroscope's *peak* speed
since the last poll (the peak, not the mean: one frame smeared by a twitch is
one tile smeared) against the ceiling; and the reticle's own movement on the
canvas between polls, allowed what the ceiling would move it in the same
time -- `omega_max · Δt · pixelsPerRadian · tileScale`, never more than the
lock radius -- so the two measures agree by construction (`MosaicGuide.configureGate`).
Where the lens is unknown the allowance is 2% of a tile per 100 ms. The hold
is the settle time of steady polls in a row, at least three of them: 170 ms
(from 250), stretched to cover the burst (`settleFor`: frames × interval +
40 ms), since the burst is the ring's newest frames and the hold *is* the
frames' exposure window. The ring fills in proportion and the burst fires
itself; there is nothing to press.

**The radius is the radius.** In and out at one lock radius, five percent
of a tile, for every node in every state; see the retired magnet above. A
tremor that carries the reticle over the edge restarts the hold, and the
hold is 170 ms, so it is not a costly restart.

**Why the old rule fired.** "Inside the radius for 250 ms, drift under a
radius" -- with the tracker at the time polling every 300 ms, the second
observation satisfied the clock, and 5% of a frame is a degree of arc: a hand
arriving on a target is inside it for a quarter second at a degree a second.

**Why the tracker was retired from guidance.** Registration of the preview
frame was measured at 219 ms a poll and brought to 25
(`ZslStreamDeviceTest.trackingIsPricedAgainstAnIdleStream`; the speedups --
in-place 320×240 view, linear-time Shi–Tomasi, coarse shortlist, adaptive
RANSAC -- remain, and are what registers each burst). But on a lens seven
degrees across a pan of a few degrees carries the anchor's features out of
the frame; the registration clung to whatever was left at the edge, then
failed, and the dots stuck, swam, snapped and vanished. A hand cannot follow
a dot that does that. Registration is the right tool for placing a tile that
has been taken -- exact, and against the pixels -- and the wrong one for
saying where the phone points *now*.

**The lifecycle.** Super Res is a mode with a state machine
(`SuperResMachine`: IDLE → ARMED → RUNNING → STOPPING → ARMED), applied by the
screen and walked by `SuperResMachineTest`. The run is a coroutine job, not a
`LaunchedEffect` keyed on a flag: the first version's STOP flipped the flag,
which cancelled the effect and with it the code after the run that re-enabled
the controls -- the screen was left armed, busy and unreachable. Now the loop
returns when `Phase.shouldContinue` reads false or the stream closes, and the
`finally` puts the machine back to ARMED, frees the controls and clears the
job whatever happened inside; the runner's own `finally` stops the sensors
and unlocks the stream; `session.use` frees the canvas. Selecting the mode
switches to the telephoto and turns the ring on if it was off; leaving it
puts both back. The system back gesture is `BackNavigation`'s stack: a sheet,
the gallery, the settings, then STOP if running, then leave the mode, and
only on a bare viewfinder in an ordinary mode does it exit.

**The colour.** Unrelated to the gate but found by the same test: the lock
taken before AE and AWB had converged on a fresh stream froze white balance
at unity, and the whole mosaic was green. `ZslRawStream.controlsConverged`
is waited on (up to 3 s) before the lock, and the anchor frame's result must
carry `COLOR_CORRECTION_GAINS`.

### 3.5 Bilateral Grid Local Tone Mapping

**As built** (`Tone.h` `BilateralGrid`, `BilateralGrid.kt` as the reference,
in both develops behind `DevelopParams.localTone`; off by default, the
`LOCAL TONE` control turns it on at 0.5).

```
Input: linear RGB after the colour matrix and the exposure gain, before the
global roll-off. Luminance L = 0.2126 R + 0.7152 G + 0.0722 B.

Grid: cells 16 px square, 16 bins over log2 L in [-10, +2] (0.8 stop each).
      Each cell: (sum of log2 L, count).

Splat: one sample per 2x2 CFA block -- the block's red, the mean of its two
       greens, its blue, through the matrix and the gain -- into the cell at
       (x/16, y/16, bin(log2 L)). A quarter of the pixels, no demosaic; the
       grid is coarser than that by two orders anyway.
Blur:  [1 4 6 4 1] / 16 along each of the three axes in turn.
Slice: per pixel, trilinear at (x/16, y/16, log2 L) -> base b.
Pivot: the count-weighted mean base of the whole grid.
Gain:  k = 2 ^ (-strength * (b - pivot));  R, G, B *= k.
```

The detail layer `log2 L - b` is carried through unchanged because every
pixel with the same base gets the same gain; the base layer's range about the
picture's mean shrinks by the strength. Then the global chain proceeds. The
spec's "global tone curve applied to the base" is this: the shoulder and the
display curve see a picture whose range has been brought in locally.

Three properties, each a unit test (`BilateralGridTest`): a bright region
comes down and a dark one up, meeting at the pivot at full strength; a pixel a
fifth of a stop above its neighbours stays exactly that far above them; and a
dark pixel four pixels from a hard bright edge gets the dark side's gain within
8%, not a blend -- which is the halo a spatial blur would produce and the third
axis prevents. The operator is hue-preserving because it is one scale per
pixel. Native and Kotlin agree within the develops' own bound of two codes
(`DevelopParityTest.nativeAndKotlinLocalToneAgreeAndAct`,
`LinearDevelopParityTest.withTheLocalOperatorItAgrees`).

Not applied to Super Res tiles: a base computed per tile differs at every seam.
The right grid for a mosaic is one over the canvas, which is future work.

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

**The hole fraction is predicted before anything is accumulated.** The
geometry that produces it is in the alignment fields. A frame's samples land at
`2 * ax - 4 * (dx + fracDx)`: four canvas cells per proxy pixel, so the integer
part moves a sample by a multiple of the period of every channel's pattern and
changes nothing about which cells are reached. Only the fraction does.
`SubPixelCoverage.predict` takes, per tile and per channel, the positions
modulo four of the reference's sites and every frame's shifted sites, and
counts the sixteen positions of a period that lie within the kernel's reach
(Euclidean distance ≤ 1, where the kernel's weight is 0.135 against the 0.05
hole threshold) of none of them. On a burst whose frames agree with the
reference this is the measured fraction exactly; `SubPixelCoverageTest` holds
it to the accumulator on the tripod and quad-shift cases, and on the
full-size synthetic burst of `SuperResDeviceTest` the prediction was R 19.6%,
G 0.7%, B 19.6% against a measurement of 19.8 / 0.8 / 19.7.

The decision is per channel, because the channels are not alike:

| Channel | Sites per period | Bound on interior holes | Why |
|---|---|---|---|
| Green | 2 of 4 | **2%** | Carries the resolution claim. A handheld burst of eight leaves about one cell in fifty; a tripod burst six in sixteen. |
| Red, blue | 1 of 4 | **25%** | Each frame reaches a quarter of what green reaches; eight frames at random offsets leave about a fifth for the fill. The ordinary develop *interpolates* red at three sites in four from neighbours four cells away, so a canvas with a fifth of its red cells filled from within three is still three times the measured chroma. A quarter is where that stops being true. |

Both bounds are checked twice: on the prediction, which is the reason not to
run; and on the measurement, which is the confirmation, since a sample the
robustness weight rejects contributes less than the geometry promised. A
canvas that fails either is closed and the ordinary merge -- which ran anyway,
see §3.6.5 -- is what the user gets, with the reason in the readout.

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

**As built and measured** (`SuperResDeviceTest`, 8 frames of 4080×3072 on the
Pixel 11 Pro): the canvas is a 287 MB anonymous mapping of 16-bit RGB; the
strip accumulators are two 49 MB planes (weighted sum and weight) for a 256-row
strip with a three-row halo, resolved in place so no third plane exists; and
the develop that follows (§3.6.5) releases the canvas's pages strip by strip as
it consumes them, so its 150 MB of 8-bit output grows as the canvas shrinks and
the footprint never exceeds the canvas. Resident memory across the run: 257 MB
before the frames, 451 MB with eight synthetic frames held, **734 MB at the
peak** with the canvas resolved, 462 MB after the develop with the frames still
held. Nothing of image size touches the Java heap at any point.

**The sweep is memory-bound, and its shape follows from that.** One thread
places a sample in about 45 ns. Eight threads over full-width row bands managed
1.6× that, not 8×: each sample's nine taps are read-modify-writes into planes
of fifty megabytes, and eight cores doing that at once are eight cores waiting
on the same DRAM. The sweep is therefore done in **tiles** of the accumulator,
32 canvas rows by 256 columns -- under 200 KB across both planes and three
channels, resident in a little core's L2 for the whole of the tile's sweep
across every frame -- claimed dynamically because the cores are not alike. A
tile is swept by every sensor row and column whose samples can reach it, its
own extent plus the alignment's reach, and a tile's own cells are the only
ones it writes, so the tiles are bit-identical to the single sweep they
replaced. Measured interleaved on one burst: full-width bands of 8 rows
771 ms, of 32 rows 1271 ms; tiles of 64×512 661 ms, 128×1024 722 ms, 32×256
**534 ms**, against 1140 ms single-threaded. The Gaussian is a 4096-entry
table on the squared distance, interpolated, in place of nine hundred million
calls to `exp`; the interpolation error is a few parts in 10⁸ and the parity
test's bound is one 16-bit count.

#### 3.6.5 What This Does to the Develop Chain

For bursts taking this path, §3.1 changes:

```
Merged CFA -> black -> hot pixel -> shading -> white balance
  -> DEMOSAIC                                    <-- removed
  -> colour matrix -> tone curve -> sRGB gamma -> sharpen
```

becomes

```
Reference CFA -> hot pixel                       <-- on the reference, before placement
  -> SUB-PIXEL ACCUMULATION (this section)       <-- replaces demosaic, outputs
                                                     16-bit RGB at 2x in sensor counts
  -> black -> shading -> white balance           <-- per channel, on the canvas
  -> colour matrix -> tone curve -> sRGB gamma -> sharpen
  -> JPEG                                        <-- from native memory to the descriptor
```

**Where the preparatory passes sit, and why.** An earlier draft of this section
put black, shading and white balance ahead of the accumulation. As built they
follow it, and the reasons are exact rather than convenient. Black subtraction
is a per-channel constant offset and a weighted mean of `(v - b)` is
`(mean v) - b`; the two greens' levels differ by the sensor's green imbalance
and are averaged. White balance is a per-channel constant gain and commutes
with the mean for the same reason. Shading varies smoothly across the sensor
and is evaluated at the canvas cell's own sensor position, which is within one
sensor pixel of where any sample that reached it was taken -- a difference of
a few thousandths of a percent in gain, below the map's own resolution. Moving
them after the accumulation also means the accumulator has one job and one
parity test.

**Hot pixels are the exception and stay ahead.** A defective site in the
reference survives the robustness weighting at full weight -- every alternate
disagrees with it and is rejected -- and once the kernel has spread it into a
blob three cells across no same-colour-neighbour test on the canvas recognises
it. So the rule of §3.1 (a site beyond the range of its four same-colour
neighbours by the threshold is replaced by the nearest of them) is applied to
each reference sample as it is placed, on the sensor grid. Alternates need no
such treatment: their defects disagree with the reference and are rejected.
Defringe is absent because the canvas was built from aligned measured samples
rather than interpolated across colour planes, and has no demosaic fringes.

**The develop is native, from the canvas's pages to the file.** A 50 MP canvas
developed to RGBA is 200 MB, which cannot be a Bitmap beside a 256 MB heap
already holding a ring of raw frames. `LinearDevelop.cpp` develops the canvas
in strips into three-byte RGB in a second anonymous mapping, releasing each
strip of the canvas with `madvise(MADV_DONTNEED)` as it is consumed; sharpens
that in place with the §3.1 operator on a band-local luma; and hands it to the
strip JPEG encoder (`Jpeg.h`), which writes to the descriptor `ImageSaver`
opened. The pixels are written in the sensor's orientation and the EXIF
orientation tag says which way is up, because turning fifty megapixels is a
second copy of them. Everything from the colour matrix onward is `Tone.h`, the
same functions the CFA develop runs, so a 2x photograph and its ordinary
sibling render the same tone; `LinearDeveloper.kt` is the Kotlin reference and
`LinearDevelopParityTest` holds the native path to it within two codes, one
with the sharpen off. On the full-size synthetic burst the develop took 850 ms,
the sharpen 150 ms and the encode 220 ms.

**The DNG beside it is the ordinary merge's.** A DNG is the sensor's CFA;
`DngCreator` writes nothing else, and the canvas is not that. So the 2x path
runs the ordinary merge as well -- the same frames placed by the same
alignment fields, accumulated the §3.2 way -- and writes its DNG under the
same name as the 2x JPEG. That DNG is a raw file that is true, and it is also
what is developed when the canvas declines at any of its three gates
(prediction, allocation, measurement), so a decline costs the user nothing but
the resolution they asked for. The pair carry one name and one gallery entry.

**This does not replace the existing merge.** A single frame, a burst without
the diversity, a device short of memory, or a user who has not asked all take
the §3.2 path. The 2x path is asked for by a setting (`AppSettings.superRes`,
the `2X` toggle, shown only where `CameraCapabilities.supportsSuperRes` holds),
and which one ran is recorded in the readout as `· 2X` or `· 2X declined`,
in the file name as `_sr2x_`, in the EXIF description as "accumulated at 2x",
and in the gallery as a `SUPER-RES 2X` badge -- because a 2x output is a
different claim about the photograph and should say so everywhere the
photograph is.

#### 3.6.6 Verification

| Property | Test |
|---|---|
| A/A null | A burst of one frame, accumulated, must match that frame demosaiced to within the kernel's known softening. It cannot invent detail from a single sample set. |
| Synthetic ground truth | Render a known high-resolution target, sample it through a simulated Bayer grid at known sub-pixel offsets, accumulate, and compare against the original. The recovered detail is measurable rather than a matter of opinion. |
| Native/Kotlin parity | As `MergeParityTest` does for §3.2 — a reference implementation in Kotlin, bit-comparable to the NEON path within the `-ffast-math` bound of 1e-5. |
| Hole fraction | Recorded per capture, per channel; predicted from the alignment before the accumulation and held to the measurement (`SubPixelCoverageTest` exactly on agreeing frames; `SuperResDeviceTest` within a point at full size). |
| No-diversity guard | A tripod burst predicts 11/16 red and 6/16 green holes and is declined before anything is allocated (`SubPixelCoverageTest.aTripodBurstIsPredictedExactly`). |
| Sub-pixel bias | The recovered fraction swept against a known shift at the sensor's full width (`SubPixelBiasDeviceTest`); the curve is the finding, see §3.3.1. |
| Linear develop parity | Native against `LinearDeveloper` on a canvas the native accumulator built, every stage on: two codes with the sharpen off, four with it on and fewer than one value in a thousand past two (`LinearDevelopParityTest`). |
| Tone equality | A flat field through the canvas and the linear develop is the same grey as through the CFA develop, to one code (`LinearDeveloperTest.aFlatFieldRendersTheSameToneAsTheCfaDevelop`). |
| End to end | Eight full-size frames to a decodable 8160×6144 JPEG with the orientation in the tag, timings and resident memory logged (`SuperResDeviceTest`). |

---

## Appendix: File Inventory by Subsystem

### Pipeline (app/src/main/java/dev/multiframe/camera/pipeline/)

| Subsystem | Files |
|---|---|
| **Capture & ZSL** | `ZslRawStream.kt`, `ZslCapture.kt`, `ZslPolicy.kt`, `RawRing.kt`, `BurstBuffer.kt`, `RawCapture.kt`, `RawBurstCapture.kt` |
| **Merge** | `BayerMerger.kt`, `BayerFrame.kt`, `Merger.kt`, `NativeMerge.kt`, `Aligner.kt` |
| **2x canvas** | `SubPixelAccumulator.kt` (reference), `NativeSubPixel.kt` (strips, and the develop entry points), `SubPixelCoverage.kt` (the prediction and its bounds), `LinearDeveloper.kt` (the linear develop, reference), `SuperResCapture.kt` (the outputs) |
| **Develop** | `RawDeveloper.kt`, `Demosaic.kt`, `ToneCurve.kt`, `BilateralGrid.kt` (local tone, reference), `ColorScience.kt`, `ColorProfile.kt`, `LensShading.kt`, `Sharpen.kt`, `Defringe.kt`, `HotPixels.kt`, `ToneAblation.kt` |
| **Output** | `ImageSaver.kt`, `NativeJpeg.kt`, `ExifWriter.kt`, `DngMetadata.kt` |
| **Exposure** | `ExposureStrategy.kt`, `CaptureMode.kt` |
| **Camera** | `CameraCapabilities.kt`, `LensCatalog.kt`, `LensProfile.kt`, `ManualSettings.kt`, `TouchFocus.kt`, `FocusPeaking.kt`, `OrientationTracker.kt` |
| **DCG calibration** | `DcgCalibration.kt`, `DcgProfiler.kt`, `DcgCalibrationStore.kt` |
| **Super Res (mosaic)** | `SuperResMachine.kt` (the mode's lifecycle), `MosaicSession.kt`, `MosaicCapture.kt` (the guided runner), `ImuGuidance.kt` (the dots, from the attitude), `MotionSensor.kt` (gyroscope and rotation vector), `StabilityGate.kt` (the ceiling, the shutter floor), `MosaicGuide.kt` (the lock rule, the magnet), `MosaicPlanner.kt` (presets, regions, nodes), `MosaicAssembler.kt` (anchor, locate, place), `MosaicCanvas.kt`, `MosaicRefiner.kt` |
| **Memory** | `MemoryBudget.kt`, `MemoryPressure.kt` |
| **Settings** | `AppSettings.kt`, `CaptureReadout.kt`, `RecentCapture.kt`, `CaptureLog.kt` |
| **GPU** | `GpuCrossing.kt` (benchmark only) |
| **Geometry** | `Homography.kt`, `FeatureMatcher.kt` |
| **Sensor** | `LevelGauge.kt`, `YuvFrame.kt`, `TripodDetector.kt`, `DitherController.kt`, `DitherPulse.kt` |

### Native C++ (app/src/main/cpp/)

| File | Purpose |
|---|---|
| `bayer_merge.cpp` | NEON-vectorized merge accumulation, noise estimation, shading correction, prepass, the CFA develop. |
| `Tone.h` | The tone chain shared by both develops: gamma and display tables, the shoulder table, `renderLinear`, shading interpolation, the band scheduler, and the `BilateralGrid` local tone operator (§3.5). Header-only. |
| `SubPixel.cpp` | Sub-pixel kernel accumulation onto a 2x canvas, strip-major over an mmap output, tiled for cache residency (§3.6). Held to `SubPixelAccumulator.kt` within one 16-bit count. Also the JNI for the canvas's develop. |
| `LinearDevelop.cpp` / `.h` | The develop for the 2x canvas: black, shading, white balance, matrix and tone from `Tone.h`, sharpen on band-local luma, into three-byte RGB with the canvas released strip by strip, then the strip encoder (§3.6.5). Held to `LinearDeveloper.kt`. |
| `Align.cpp` | Native 4-level pyramid tile alignment with equiangular sub-pixel refinement at the finest level (§3.3.1). Held to `Aligner.kt` at zero differing tiles. |
| `RingBuffer.cpp` / `.h` | mmap-backed AHardwareBuffer ring buffer |
| `Mosaic.cpp` / `.h` | Native mosaic canvas: running-mean compositing in three frequency bands (§3.4.3), flattening and compression from its own pages |
| `Rotate.cpp` | NEON-accelerated image rotation |
| `Jpeg.cpp` / `.h` | libjpeg-turbo strip encoder, parallel; the header exposes it to the linear develop for RGB from native memory |
| `GpuCrossing.cpp` | Vulkan crossing cost benchmark (not production) |

### UI (app/src/main/java/dev/multiframe/camera/ui/)

| File | Purpose |
|---|---|
| `Theme.kt` | `Ink` palette, `Type` scale, `Haptics` vocabulary, `MultiframeTheme` |
| `Gauges.kt` | `GaugeState` holder and the live readouts: exposure, histogram, focus, zoom |
| `ControlBar.kt` | Control data model: `ControlSpec`, `ControlBarState`, `Detents`; the builders `chrome` (the one opener over the picture), `pro` (the manual drawer's opener beside the shutter), `settings` (the settings screen's chips: FRAMES, TIMER, DNG, GUIDES, HISTOGRAM, INFO), `carousel` (AUTO · NIGHT · ACTION · SUPER RES), `actions` (STOP, while a Super Res runs; nothing otherwise) |
| `ControlChips.kt` | Composable rendering: `ChromeOpeners`, `SettingsChips`, `ModeCarousel`, `ActionStrip`, `ControlChip` |
| `SettingsScreen.kt` | Everything set once, on a screen of its own: the settings chips and the set-once panel |
| `ControlsPanel.kt` | `ManualPanel` -- exposure, focus, WB, in the PRO drawer over the bottom bar; `SetOncePanel` -- GUARD, 2X, LOCAL TONE, HEIF, reset, the capability summary, on the settings screen |
| `BackNavigation.kt` | What the system back gesture does, given what is open: a pure stack, tested |
| `Gallery.kt` | Gallery grid, review overlay with pixel-level zoom, delete confirmation; its own back handler |
| `Guides.kt` | Composition guides (rule of thirds, golden ratio, etc.), `MosaicGuidance` -- the outline, the reticle and the dots -- and `MosaicPresetPill` (`2×2 · 33 MP | 3×3 · 75 MP`) |
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
