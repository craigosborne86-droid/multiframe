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
- **After step 7, before step 8 (Phase 3):** Bilateral-grid local tone mapping.

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

Tile-based alignment with sub-pixel refinement:

```
The image is divided into tiles (typically 32x32 pixels in the 2x-downsampled
luma proxy).

For each tile:
  1. Extract the tile from the reference proxy.
  2. Search a +-16 pixel window in the alt proxy for the minimum SAD
     (Sum of Absolute Differences).
  3. Record (dx, dy) for the minimum.
  4. Sub-pixel refinement: fit a parabola to the 3x3 SAD neighborhood
     around the minimum and find the analytic minimum.

Output: AlignmentField(tilesX, tilesY, dx[], dy[])

Per-pixel displacement is bilinearly interpolated from the tile grid.
```

The luma proxy is computed with `gainScale` normalization: `proxy_value = (raw /
gainScale)`, so brightness differences from ISO bracketing don't confuse the SAD
metric.

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

### 3.5 Bilateral Grid Local Tone Mapping (Phase 3)

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

## Appendix: File Inventory by Subsystem

### Pipeline (app/src/main/java/dev/multiframe/camera/pipeline/)

| Subsystem | Files |
|---|---|
| **Capture & ZSL** | `ZslRawStream.kt`, `ZslCapture.kt`, `ZslPolicy.kt`, `RawRing.kt`, `BurstBuffer.kt`, `RawCapture.kt`, `RawBurstCapture.kt` |
| **Merge** | `BayerMerger.kt`, `BayerFrame.kt`, `Merger.kt`, `NativeMerge.kt`, `Aligner.kt` |
| **Develop** | `RawDeveloper.kt`, `Demosaic.kt`, `ToneCurve.kt`, `ColorScience.kt`, `ColorProfile.kt`, `LensShading.kt`, `Sharpen.kt`, `Defringe.kt`, `HotPixels.kt`, `ToneAblation.kt` |
| **Output** | `ImageSaver.kt`, `NativeJpeg.kt`, `ExifWriter.kt`, `DngMetadata.kt` |
| **Exposure** | `ExposureStrategy.kt`, `CaptureMode.kt` |
| **Camera** | `CameraCapabilities.kt`, `LensCatalog.kt`, `ManualSettings.kt`, `TouchFocus.kt`, `FocusPeaking.kt`, `OrientationTracker.kt` |
| **Mosaic** | `MosaicSession.kt`, `MosaicCapture.kt`, `MosaicPlanner.kt`, `MosaicAssembler.kt`, `MosaicCanvas.kt`, `MosaicRefiner.kt` |
| **Memory** | `MemoryBudget.kt`, `MemoryPressure.kt` |
| **Settings** | `AppSettings.kt`, `CaptureReadout.kt`, `RecentCapture.kt` |
| **GPU** | `GpuCrossing.kt` (benchmark only) |
| **Geometry** | `Homography.kt`, `FeatureMatcher.kt` |
| **Sensor** | `LevelGauge.kt`, `YuvFrame.kt` |

### Native C++ (app/src/main/cpp/)

| File | Purpose |
|---|---|
| `bayer_merge.cpp` | NEON-vectorized merge accumulation, noise estimation, shading correction, prepass |
| `Align.cpp` | Native tile alignment (SAD-based matching in luma proxy) |
| `RingBuffer.cpp` / `.h` | mmap-backed AHardwareBuffer ring buffer |
| `Mosaic.cpp` / `.h` | Native mosaic tile compositing |
| `Rotate.cpp` | NEON-accelerated image rotation |
| `Jpeg.cpp` | libjpeg-turbo JNI bridge (parallel striped encoding) |
| `GpuCrossing.cpp` | Vulkan crossing cost benchmark (not production) |

### UI (app/src/main/java/dev/multiframe/camera/ui/)

| File | Purpose |
|---|---|
| `Theme.kt` | Ink palette, Material3 dark color scheme, `MultiframeTheme` |
| `ControlBar.kt` | Control data model: `ControlSpec`, `ControlBarState`, mode/action builders |
| `ControlChips.kt` | Composable rendering of chips: `ControlRow`, `ActionStrip`, `ControlChip` |
| `ControlsPanel.kt` | PRO panel: manual exposure, focus, WB, toggles, sliders |
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
