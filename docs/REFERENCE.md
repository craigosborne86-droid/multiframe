# Multiframe — Engineering & Design Reference Pack

## September 2026 camera fundamentals reset

Current user-facing modes are PHOTO and NIGHT only. Old stored AUTO migrates
to PHOTO; retired ACTION and SUPER_RES selections fall back to PHOTO. Historical
phase notes below describe earlier interfaces and do not override this reset.

- Rear lens stops: 0.5× / 1× / 2× / 5× / 10×, plus front/rear flip. Physical
  center cameras are preferred where exposed. CameraCapabilities gates the
  API 34 `CROPPED_RAW` stream use case. The HAL may un-bin at suitable zoom and
  lighting; `SCALER_RAW_CROP_REGION` identifies each frame's actual field.
- Zoom accumulation runs in both modes, preserving their exposure policy.
  `RenderCrop` maps requested framing into the reported RAW region and crops
  only after full development. It handles off-center HAL crops and avoids
  double-cropping an un-binned center frame. The DNG retains measured RAW data.
- Zoom changes invalidate readiness until metadata confirms the new request;
  subsequent RAW crop changes restart the readiness window. A short 16-frame
  canvas burst is refused, rather than mixing fields of view.
- Tap focus copies the active request's exposure, WB, zoom and targets into
  the one-shot AF trigger. AF/AE regions are separately capability-gated.
  Zoom-ratio metering uses post-zoom coordinates; legacy crop requests map
  into their sensor crop. Image bounds, display rotation and mirror are applied.
- Long manual preview exposures are clamped to 33,333,333 ns; preview ISO
  compensates within the sensor limit so the exposure aid remains usable. Explicit still
  bursts use the requested duration and validate every locked timestamp against
  their capture sequence. Preview resumes before development is queued. A
  sensor cannot produce fresh preview during the same long integration.
- Both CFA and sub-pixel robustness now use a finite-support weight. Let
  `q = residual² / noiseEnvelope²`: weight is 1 for q ≤ 1,
  `(4-q)/(3q)` for 1 < q < 4, and 0 for q ≥ 4. This removes the old nonzero
  tail that could accumulate displaced silhouettes across many frames. The
  sub-pixel envelope still includes interpolation uncertainty. Reference
  samples keep unit weight; gain normalization remains black + (raw-black)/g.
- Viewfinder sizing keeps a 45 dp turret and 322 dp console invariant in both
  PRO states. The shutter occupies a permanent 130 dp band beneath the 43 dp
  console header; only readouts beside it and controls below it swap. PRO,
  histogram and optional manual rows never feed their measured heights back
  into the preview.
  Guides use actual surface bounds; landscape metadata stays on the edge rail.
  Manual-focus peaking is composed in the preview's OpenGL draw from the same
  latched camera texture. A multiscale sharpness check marks sparse resolving
  detail in cyan at no more than 28% opacity. There is no timed RAW mask or
  separately drawn peaking overlay to trail the live image.
- Gallery uses HorizontalPager. Single-finger gestures pass to the pager at
  scale 1; transformed images consume pan. Zoom uses centroid minus viewport
  center, and pan limits use the fitted image dimensions, not letterbox space.
- DNG orientation tags: 0°→1, 90°→6, 180°→3, 270°→8; the front-facing device
  rotation sign is reversed. Both ordinary and canvas sidecar writers set it.



**Version:** 1.0
**Date:** 2026-09-10

This document is the authoritative reference for implementation. When this
document and a code comment disagree, this document is correct and the code
comment should be updated.

---

## 1. Design System & Joy Reference

### 1.1 The Titanium Rangefinder Palette

#### 1.1.1 Philosophy

The interface is a milled instrument: a charcoal chassis, titanium plates,
white telemetry and one amber accent. Three rules decide the palette:

1. **Nothing coloured sits on top of the photograph.** A saturated hue in
   peripheral vision shifts the user's perception of the colours in the image.
   A camera whose argument is neutral rendering cannot introduce that bias.
   Marks on the photograph (focus brackets, focus target, loupe frame) are
   `TelemetryWhite`.

2. **Amber belongs to the console.** `OpticalAmber` marks the selected
   control (a short underline and the label's weight) and every live
   reading. It is never a fill, a background or an icon, and never on the
   photograph.

3. **A control the camera cannot use is absent, not greyed.** `TelemetryDim`
   on a label means "momentarily busy" (a capture in flight), never "this
   hardware cannot".

Three documented exceptions to rule 1: `Level` (green, the level gauge),
`Clipping` (red, blown highlights) and `Peaking` (cyan, manual focus). Each is
a transient state about the photograph itself, not about the interface. Two
opt-in marks are tolerated: the preview RGB histogram (the `RGB` button) and
the Labs Brenizer sweep marker.

#### 1.1.2 Palette Tokens

All values live in `TitaniumTokens.kt`. `Ink.*` in `Theme.kt` are aliases for
older call sites and must never carry a value of their own.

| Token (`TitaniumTokens`) | `Ink` alias | Hex | Purpose |
|---|---|---|---|
| `Chassis` | `Ground`, `OnBone` | `#0A0A0C` | App background, viewfinder fallback |
| `PlateSurface` | `Pane`, `PaneSolid`, `Panel` | `#121316` | Control plate, panels, settings rows |
| `HairlineBorder` | `Hairline` | `#22242B` | Plate borders and turret ruling |
| `FrameLine` | `Edge` | `#3A3E4D` | Frame of image-containing elements |
| `TitaniumM6` / `M6Dark` | `OnBoneMuted` (dark) | `#C8C2B8` / `#8C867C` | Plate finish gradients, pressed tint |
| `TelemetryWhite` | `Bone`, `SheetBody` | `#E6E6EB` | Primary text; every mark on the photograph |
| `TelemetryDim` | `Muted`, `SheetMuted` | `#7A7E8C` | Labels, units, "no reading", busy controls |
| `OpticalAmber` | `Amber` | `#FF9E1B` | Selected control and live readings, console only |
| `ProofVelvet` | `Sheet` | `#060708` | Opaque sheets (About, Lightbox ground) |

**Exception tokens (the only colour on the photograph):**

| Token | `Ink` alias | Hex | Purpose |
|---|---|---|---|
| `PhosphorGreen` | `Level` | `#00E599` | Level gauge within tolerance (0.5°) |
| `ClippingRed` | `Clipping` | `#FF2D2D` | Blown highlight warning. Never aliased to amber or `Danger` |
| `SignalCyan` | `Peaking` | `#00D2FF` | Focus peaking while manual focus is engaged; used for nothing else |
| `HistogramRed/Green/Blue` | -- | `#FF9292` / `#A3E5A8` / `#9BC8FF` | Opt-in preview RGB histogram |

**Semantic tokens (off the viewfinder):**

| Token | `Ink` alias | Hex | Purpose |
|---|---|---|---|
| `Danger` | `Danger` | `#E85B5B` | Destructive actions in Lightbox and dialogs |
| `PhosphorGreen` | `Confirm` | `#00E599` | Success in Lightbox/onboarding; Labs sweep "steady" |

Geometry tokens: `RadiusPlate` 4dp (every control, settings row and file
action), `RadiusSmall` 2dp (inset segments), `MinimumTouch` 48dp (the floor),
`ShutterDiameter` 84dp (the only circle), `Hairline` 1dp.

#### 1.1.3 Color Usage Rules

1. **Selected:** amber underline, label `SemiBold` in `OpticalAmber`.
   **Inactive:** `TelemetryWhite` label on the plate finish with a
   `HairlineBorder`. **Busy:** `TelemetryDim` label, not clickable.

2. When a value is a **live reading** (a number the camera is reporting):
   `OpticalAmber`. When a value is **the word for not having a reading**
   (e.g., "OFF"): `TelemetryDim`.

3. `Danger` and `Confirm` are **never** used on the viewfinder during normal
   operation. They appear only in Lightbox actions, onboarding, error dialogs
   and the Labs sweep guidance.

4. The exception tokens -- `Level`, `Clipping`, `Peaking` -- are the only
   chromatic marks over the photograph outside the two opt-in marks, and
   each is a *transient state about the photograph*: it appears when the
   thing is true and vanishes when it stops being true. `Peaking` is drawn in
   the preview's bounds and orientation, from the current preview frame,
   while manual focus is engaged and at no other time.

5. `ThemeTest` holds `Clipping`, `Amber`, `Danger` and `Peaking` apart. Do
   not merge two of them to save a token.

---

### 1.2 Typography Scale

#### 1.2.1 Type Tokens (as `TextStyle` values in `TitaniumTokens.kt`)

Every interface face is monospace (`TitaniumTokens.Technical`) with 1.2sp
tracking; prose in sheets and panels uses `Readable` (sans-serif 14sp) and
`ReadableSmall` (12sp).

| Token | Size | Colour | Use |
|---|---|---|---|
| `Gauge` / `GaugeCompact` / `GaugeLarge` | 13 / 17 / 26sp | Amber | Live readings |
| `GaugeBone` | 13sp | TelemetryWhite | A number that is not a live reading |
| `SelectedValue` | 26sp | Amber | The selected control's value in a panel |
| `Label`, `Section`, `Unit`, `Instruction` | 10–11sp | TelemetryDim | Labels, headers, units |
| `Control`, `Action`, `Status` | 12sp | TelemetryWhite | Buttons, status line |
| `LensFocal` / `LensZoom` | 13 / 10sp | TelemetryWhite / Dim | Turret stops |
| `Countdown` | 76sp | TelemetryWhite | Self-timer digits |
| `Badge` | 10sp | Amber | Output badges (SR, 50 MP) |

#### 1.2.2 Usage Rules

- **Monospace** is the instrument face: readings, labels and controls alike,
  so a console reads as one engraved plate.

- **Proportional** (`Readable`, `ReadableSmall`) is used for prose: panel
  explanations, sheets, onboarding and Lightbox details.

- **Never mix** a proportional label and a monospace label for the same kind of
  element. If one chip uses monospace for its value, all chips use monospace for
  their values.

- The `fontSize` values in the token table are the **canonical sizes**. Do not
  write `fontSize = 13.sp` directly in a composable — apply the style:
  `Text(text = value, style = TitaniumTokens.Gauge)`.

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
    // dialDetent uses the device vibrator's predefined EFFECT_CLICK;
    // see Theme.kt for the unsupported-device fallback.
    // SEGMENT_FREQUENT_TICK on API 34+, CLOCK_TICK on API 33, which lacks it.
    fun segment(view: View)    = view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
    fun reject(view: View)     = view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    fun confirm(view: View)    = view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
}
```

#### 1.3.2 Mapping Table

| Context | Trigger | Haptic Call | Rationale |
|---|---|---|---|
| Shutter pressed | `onClick` on shutter button | `Haptics.mechanicalRelease` (two short impacts, `VibratorManager` waveform) | A photograph was taken. It fires only after the frames are locked (ZSL, `CaptureFeedback.afterSync`) or captured (sequential, `CaptureFeedback.afterSuspending`), never during them. The press itself is silent: the half-press haptic was removed because the ring is recording while the finger is down |
| Shutter refused | Ring full, ring not refilled, or fewer frames ready than the press asked for | `Haptics.reject` | Nothing was taken; the status says so. A press never takes a short burst |
| Self-timer tick | Each countdown second but the last | `Haptics.tick` | Light metronome, not distracting. The last second is silent: the frames the timer locks are exposed in it |
| Mode toggle | GUARD, LOCAL TONE, HDR OUTPUT, HISTOGRAM, DNG, Labs Brenizer 2×2/3×3 changed | `Haptics.segment` | Detent feel, like a mechanical switch |
| Cycle value | FRAMES, TIMER, GUIDES, NIGHT's Long and Astro spans stepped | `Haptics.segment` | Same detent feel for any discrete step |
| Mode selected | PHOTO or NIGHT chosen in the carousel | `Haptics.segment` | A dial detent; arming Brenizer Sweep also switches the lens, which has the same detent |
| Super Res stopped | STOP tapped, or the back gesture while running | `Haptics.segment` | Acknowledging the request; the stop itself lands a moment later and is not felt |
| Slider at detent | ISO at round number (100, 200, ...), EV at 0 | `Haptics.tick` | Subtle stop point, not every pixel of drag |
| Rangefinder manual dial | Each selected ISO, shutter, focus, frames or colour value | `Haptics.dialDetent` (`EFFECT_CLICK`) | A stronger, distinct click for each selected value; suppressed while a burst is in flight. Release coasts at most half a detent before snapping to the nearest value |
| Lens switch | New lens selected in strip | `Haptics.segment` | Camera is physically switching |
| Super Res anchored | First frame taken; the dots appear | `Haptics.segment` | The capture has begun; same detent as the lens switch that preceded it |
| Super Res node taken | Reticle has held still on a dot through the stability gate, the fresh frames exposed after the lock are in hand and the node is done | `Haptics.confirm` | "Got it" — eyes are on the subject. Fires *after* the last fresh frame is exposed and the burst is snapshotted, never before or during: the frames a haptic could blur are already in hand. Nothing fires at the lock itself, which only begins the exposure. This is the photograph; nothing afterwards can un-take it |
| Super Res tile placed | The node's tile is on the canvas, by its pixels or by the attitude | `Haptics.tick` | Light: the canvas caught up; which tier placed it is the log's business |
| Super Res tile rejected | The burst could not become a tile at all: the hand did not hold for the fresh frames within 2.5 s, the ring was empty, the develop failed, the canvas would not take it | `Haptics.reject` | "Try again" — also eyes on subject; the dot flashes red for a beat. Never for a registration that found nothing: that tile is placed by the attitude |
| Mosaic pan too fast | Frame centre moved most of a tile between polls | `Haptics.reject` | Rate-limited to one per 800 ms; a fast pan is a blurred frame everywhere along it |
| Mosaic complete | Every node captured | `Haptics.confirm` | The mosaic has finished itself |
| Error action | Tapping a disabled control, invalid state | `Haptics.reject` | Something went wrong |
| Settings open/close | The gear over the picture; DONE or the back gesture | (none) opening, `Haptics.segment` closing from DONE | Opening a screen is its own feedback; DONE is a control and gets the detent |
| PRO mode on/off | PRO in the console header; the back gesture | `Haptics.segment` | Changes actual AE and white-balance requests as well as the drawer |
| Lightbox delete confirmed | After confirmation dialog | `Haptics.confirm` | Acknowledge destructive action |

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

**As built** (`ui/WindowLayout.kt`): one pure decision,
`CameraLayouts.decide(widthDp, heightDp, deviceDegrees, fold)`, gives a
`CameraLayout` -- a `Posture`, a `WindowClass` at the breakpoints above
(computed from the configuration's dp; no `material3-window-size-class`
dependency, since the table is three numbers), the hand's turn, the rail's
width, the hinge's split -- and `rememberCameraLayout` keeps it current
from `LocalConfiguration`, `WindowInfoTracker.windowLayoutInfo` (the fold)
and `OrientationTracker.onQuarterTurn` (the hand). `CameraScreen` asks it
in four places and nowhere else: the viewfinder's region
(`previewRegion` + `fitAspect`, the largest box of the stream's shape that
fits the region, never stretched, never spilling), the HUD's region, the
controls' region, and the ground behind the controls. `CameraLayoutsTest`
holds the decision for every window and hand; `HandLayoutTest` the turn
and the fit.

| Posture | When | Viewfinder | Controls |
|---|---|---|---|
| `PORTRAIT` | Tall window, phone upright | Full bleed behind transparent system bars and fixed controls | The strip along the bottom |
| `HAND_LANDSCAPE` | Tall window held sideways because the host has not rotated it | As portrait | Fallback: the strip's content turns with the hand (`UprightInHand`, `Modifier.rotateLayout`) |
| `SIDEBAR` | Window wider than tall (phone landscape, tablet, foldable open in landscape) | Fitted into the first two thirds | A rail down the last third on `Ink.Panel`, vertically centred |
| `TABLETOP` | `FoldingFeature` half-opened with a horizontal hinge | The pane above the hinge | The pane below it |

#### 1.4.3 Foldable Support

When `WindowInfoTracker.windowLayoutInfo` reports a `FoldingFeature` with
`state == HALF_OPENED` and a horizontal hinge, the layout is `TABLETOP`:
split at the hinge's bounds, the viewfinder above (the half nearer the
rear cameras in the postures a foldable is used in), the controls below.
A vertical hinge (book posture) is decided by the window's shape like any
other window. A phone reports no folding feature and is the flat case.

#### 1.4.4 Orientation

- The Activity requests `fullSensor` orientation and does not override
  orientation configuration changes. Android recreates it on a quarter-turn;
  CameraX use cases bind to the new display rotation and the RAW surface is
  reopened. The capture UI uses `SIDEBAR` on a landscape phone, with the
  preview fitted into the left two thirds and the controls in the right third.
  PRO state, its selected tab, zebra, LONG EXP selection and digital zoom are
  saveable across that recreation; capture settings are restored from
  `AppSettings`.
- `HAND_LANDSCAPE` is only a fallback when a host keeps a portrait window
  while the physical device turns. It does not replace the landscape layout.
- `OrientationTracker` continues to determine capture/EXIF rotation from the
  physical device independently of the UI window.
- The Camera2 RAW viewfinder composes peaking in the same GPU pass as its
  `SurfaceTexture` image. The texture matrix supplies natural orientation;
  `PeakingPreview` also applies the current display rotation in UV space.
  A synthetic four-quadrant test checks the quarter-turn transform.

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
| Telephoto has no RAW | `LensProfile.supportsRaw == false` for telephoto | `MosaicPlanner.captureLens` falls to the longest raw lens; with none, Brenizer Sweep is absent from Labs. |
| No attitude sensor | `MotionSensor.hasAttitude == false` | Brenizer Sweep is absent from Labs: the guidance cannot run. |
| No OIS on telephoto | `LensProfile.hasOis == false` | Allow Super Res; the stability gate and the shutter floor are what hold the tile sharp. |
| No distortion data | `LENS_DISTORTION` not reported | Skip distortion correction. Log. Mosaic stitching proceeds without. |
| Low heap (< 256 MB) | `Runtime.maxMemory()` | Cap burst frames at 4, mosaic canvas at 20 MP. |
| No un-binned sensor mode | `REQUEST_AVAILABLE_CAPABILITIES` lacks `ULTRA_HIGH_RESOLUTION_SENSOR`, or the maximum-resolution stream map has no `RAW_SENSOR` size, or the device cannot hold the ring and merge (`MemoryBudget.canHoldFullResolution`) | The `12 MP \| 50 MP` chip is absent; the binned ring is the camera. Both development devices are here: their 50 MP sensors are 12 MP to Camera2, the un-binned centre a further physical camera (§2.4). |

#### 2.2.2 The Un-binned Sensor Mode

**As built** (`CameraCapabilities.ultraHighResolution`, `supportsFullResolution`;
`ZslRawStream.fullResolutionConfigs`, `RawStreamConfig.fullResolution`;
`ZslPolicy.evaluateFullResolution`; `RawRingBudget.FULL_RESOLUTION_CAPACITY`;
`AppSettings.fullResolution`; `ResolutionPill`). Where a sensor advertises
`ULTRA_HIGH_RESOLUTION_SENSOR`, its `RAW_SENSOR` sizes from
`SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION` are enumerated beside
the default map's and flagged; the chip on the top HUD switches
`AppSettings.fullResolution`, and the stream reopens on the flagged size --
every `OutputConfiguration` of the session declares
`SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION` (`addSensorPixelModeUsed`), every
request sets `SENSOR_PIXEL_MODE` to it (the repeating request, the DCG high
request, the AF trigger), and the preview size comes from the
maximum-resolution map, since every output of such a session has to be a
size that map lists. The policy admits an un-binned stream down to five
frames a second (`MAX_FULL_RESOLUTION_FRAME_DURATION_NS`) against the
default mode's twenty: a 50 MP readout is not a 30 fps stream anywhere, and
a burst at five a second held still is a burst. The ring is sized to five
slots -- a three-frame burst and the camera's headroom -- 500 MB at 50 MP
against the 765 MB the binned ring of thirty-two reserves; the merge's
working set at that size is another 500 MB (`MERGE_BYTES_PER_PIXEL`), so
the chip is offered only where ring and merge fit in half of physical
memory. A stream the sensor refuses un-binned falls back to the binned
ring, not to no ring. The chip's labels are the two raw sizes as the camera
reports them. Neither development device has the mode, so the path is
exercised by `ZslPolicyTest`, `MemoryBudgetTest`, `AppSettingsTest` and
`ResolutionPillTest` and gated live by `HardwareProbeDeviceTest`'s record;
the first phone that advertises it is where its session configuration is
proven.

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

**As built** (`DcgProfiler`, `DcgCalibration`, `DcgCalibrationStore`; the
trigger in `CameraScreen`, one `LaunchedEffect` per stream): the ladder
hangs from the metered point *downward* as far as a quarter-second exposure
reaches (`MAX_LADDER_EXPOSURE_NS`), at constant brightness -- ISO and time
trade exactly, so the viewfinder does not change and only the gain does --
and no higher than `SENSOR_MAX_ANALOG_SENSITIVITY` where the sensor
publishes it. Indoors the metered point on every one of the Pixel 9 Pro
XL's sensors *is* the analog ceiling, and a ladder that started there lay
entirely in digital gain, where the curve bends for reasons that are not a
conversion gain switch: the first run found a "switch" on three lenses of
five. A ladder that cannot get under the ceiling in a quarter second is
not concluded (nothing recorded; tried again in brighter light). A curve
whose variance never rises by `MIN_VARIANCE_SPAN` = 4 codes² is at the
quantisation floor -- 0, 0, 1, 1, 2 -- and reports no switch rather than
fitting the staircase. Settle is paced by the rung's exposure, since a
quarter-second rung delivers four frames a second. A shutter press cancels
a profile in progress; the camera goes back to its settings. A manual
exposure -- the profiler's or PRO's -- releases the shutter cap's hold,
which used to outrank it in the request (§3.7).
`DcgProfileDeviceTest` runs the ladder on every raw lens for the record.

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

### 2.4 Hardware Record: the Development Devices

What the cameras said when asked (`HardwareProbeDeviceTest`, report written
to the app's external files as `hardware-probe-<device>.txt`).

**Pixel 9 Pro XL (komodo), Android 17, 16 GB.** Camera ids `0` (rear
logical) and `1`; the rear lenses are physical members of `0` and open
through it. No lens advertises `ULTRA_HIGH_RESOLUTION_SENSOR`; the 50 MP
main and 48 MP telephoto are exposed at 12 MP, and their un-binned centres
are exposed as *further physical cameras* -- the "49mm" and "220mm" below,
each the same active array as its parent at half the sensor size.

| Lens | Camera | Active array | Pitch | ISO | Analog ceiling | Shortest exposure | Raw sizes (default mode) | Distortion k1, k2, k3, p1, p2 | Intrinsics fx, cx, cy |
|---|---|---|---|---|---|---|---|---|---|
| 12mm f/1.7 | 3 via 0 | 4032×3024 (5.64×4.23 mm) | 1.40 µm | 53–2388 | 1194 | 69 µs | 4032×3024 @30, 4032×2272 @60, 2016×1512, 1920×1080 | 0.00015, −0.00100, 0.00057, 0.00070, 0.00093 | 1406.8, 2028.2, 1518.3 |
| 24mm f/1.68 | 0 | 4080×3072 (9.79×7.37 mm) | 2.40 µm | 22–11277 | 704 | 26 µs | 4080×3072 @30, 4080×2288 @60, 2032×1536, 2016×1136 | 0.09004, −0.15054, 0.10187, 0, 0 | 2852.8, 2044.9, 1537.7 |
| 49mm (main's centre) | 5 via 0 | 4080×3072 (4.90×3.69 mm) | 1.20 µm | 22–11277 | 704 | 27 µs | 4080×3072 @30 | as 24mm | as 24mm |
| 110mm f/2.8 | 4 via 0 | 4032×3024 (5.64×4.23 mm) | 1.40 µm | 49–17655 | 1103 | 69 µs | 4032×3024 @30, 4032×2272 @60, 2016×1512, 1920×1080 | 0.41214, 2.55331, −70.5304, 0, 0 | 12378.1, 2008.5, 1504.9 |
| 220mm (tele's centre) | 6 via 0 | 4032×3024 (2.82×2.12 mm) | 0.70 µm | 34–4413 | 275 | 123 µs | 4032×3024 @30 | as 110mm | as 110mm |

All: white level 1023, black 64 on every site, orientation 90, raw and
manual sensor and manual focus on every lens, OIS on all but the 12mm.
CFA: the 12mm reports pattern 0 (RGGB), the rest 2 (GBRG).

**Read noise (§2.3), indoor light, the ladder under the analog ceiling:**
24mm 2–3 codes² flat from ISO 190 to 704 (no switch); 49mm 2–7 rising
(none, F 5.5); 12mm 1–15 (none, F 10.5); 110mm 1–13 (none, F 7.4); 220mm
0–2, the quantisation floor (none). No conversion gain switch is visible
in any lens's raw; DCG brackets at the software ratio of four.

**Pixel 11 Pro (grizzly), Android 17, build CD1A.260905.001.B1.** Camera
ids `0` and `1`; three rear lenses, all physical members of `0`; no crop
cameras and, again, no lens with `ULTRA_HIGH_RESOLUTION_SENSOR`. Ring of 32
slots at 763 MB on the main.

| Lens | Camera | Active array | Pitch | ISO | Analog ceiling | Shortest exposure | Raw sizes | Distortion k1, k2, k3, p1, p2 | Intrinsics fx, cx, cy |
|---|---|---|---|---|---|---|---|---|---|
| 12mm | 3 via 0 | 4032×3024 (5.64×4.23 mm) | 1.40 µm | 53–2370 | 1185 | 82 µs | 4032×3024 @30, 4032×2272 @60, 2016×1512, 1920×1080 | 0.00266, −0.00201, 0.00043, 0.00063, 0.00001 | 1406.5, 2021.9, 1511.6 |
| 24mm | 0 | 4080×3064 (9.79×7.35 mm) | 2.40 µm | 24–6023 | 376 | 41 µs | 4080×3064 @30 | 0.09295, −0.15067, 0.09918, 0, 0 | 2833.3, 2048.9, 1538.0 |
| 105mm | 4 via 0 | 4032×3024 (6.45×4.84 mm) | 1.60 µm | 30–7473 | 1916 | 194 µs | 4032×3024 @30, 4032×2272 @60, 2016×1512, 1920×1080 | 0.09758, 13.658, −187.50, 0, 0 | 12355.0, 1982.3, 1517.1 |

The main's analog ceiling is ISO 376 -- half the 9 Pro XL's -- and its
shortest exposure 41 µs; the telephoto's is 194 µs. The read-noise ladder
has not yet run under these ceilings (first launch of the new build does
it).

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
   Compare with the four same-colour neighbours two sites away, clamped at black.
   Above their maximum by > threshold * range: replace with that maximum;
   below their minimum by > threshold * range: replace with that minimum.
   Read the unmodified source so replacements cannot propagate. The threshold
   is in units of full scale; default 0.10. Both implementations meter the
   original CFA before this prepass and retain original sensor clipping evidence.
   This avoids small gain differences becoming large chroma threshold flips.
  |
  v
3. Lens shading correction
   For each pixel: value *= shadingGain(x, y, cfaColor)
   shadingGain is bilinearly interpolated from the camera's lens shading map
   (STATISTICS_LENS_SHADING_CORRECTION_MAP).
   Applied per-channel (R, Gr, Gb, B have different shading profiles).
   Camera2's `LensShadingMap.getGainFactor` uses R / green-even-row /
   green-odd-row / B order independently of the CFA arrangement. `ShadingMap`
   converts this once to spatial CFA-site order; DNG GainMap opcodes already
   use site order. The RGB SuperRes develop converts back using the actual CFA.
   The map is the remaining correction even when `SENSOR_INFO_LENS_SHADING_APPLIED`
   is true; apply it once. Reference: Android `CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP`.
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
6a. Purity compression (`ToneCurve.compressPurity`, `Tone.h compressPurity`)
   purity p = (max − min) / max of the linear channels.
   p <= 0.70: untouched.
   p >  0.70: p' = 0.70 + 0.15 · u / (1 + u),  u = (p − 0.70) / 0.15
              every channel's distance under the peak scaled by p' / p.
   Position: straight after the matrix and before anything reads the
   pixel's luminance (the local operator's grid, the exposure gain being a
   scale it commutes with). The justification: the matrix is a linear fit to
   a colour chart, and on a spectral colour -- a rose in the sun, a sodium
   lamp -- it extrapolates far outside the chart and overshoots. The tenth
   field test's rose: the sensor saw its green at three tenths of its red
   (camera space, white-balanced); the matrix put it at one tenth, purity
   0.89, and the S-curve of step 8a took the encoded ratio from 0.28 to 0.20.
   That is a purity no display has and no eye saw, and the petals'
   gradations, which lived in the green, collapsed onto the gamut's edge as
   one slab of red. Compressing the purity before the tone chain -- the peak
   channel kept, the low channels raised, their ratio (the hue) exactly kept
   -- puts the gradation back and renders the flower the coral pink it was.
   Hyperbolic toward 0.85 so the purest colour the rendering shows keeps its
   low channels at fifteen hundredths of the peak; C¹ at the threshold.
   Seven tenths is over every ordinary colour: a clear sky sits at six to
   seven tenths after the matrix, foliage and skin well under
   (`DngAnalysisBench` prints the purity histogram; over the field test's
   six scenes under 1.5% of the pixels of any scene without a flower lie
   above it, and 62% of the rose's). `ToneCurveTest` holds the threshold,
   the continuity, the ceiling and the hue; the develop parity tests hold
   the native to the Kotlin through it.
  |
  v
6b. HDR branch -- only with HDR OUTPUT on, API 34+, JPEG (`HdrGainMap.kt`,
   `UltraHdr.kt`). Not a step of the SDR chain: a second tone mapping of the
   same linear values, read off beside it. The SDR picture is steps 1-12 as
   they are, to the last code, and the file carries the ratio of the two
   renditions as an ISO 21496-1 / Ultra HDR gain map.
     S(m) = the step-7a shoulder: display white 1, white = gain.
     H(m) = the same shoulder with its display white at P = 4 (two stops,
            `DevelopParams.HDR_HEADROOM`): identity to the knee; past it,
            min(m, white) where white <= P, the logarithm of 7a to P at
            white where white > P.
     ratio = clamp(H(m) / S(m), 1, P),  m = max(R, G, B) after 6a.
   Map: one ratio per 4x4 block, log2(ratio) / log2(max ratio) in 8 bits,
   ratio min 1, gamma 1, no offsets, full HDR at a display ratio of the max.
   Position: after 6a, because the ratio is a function of the scene peak
   that 7a reads, and the peak is only defined once the matrix and the
   purity compression have made it -- before the matrix there is no
   display-referred peak, and a ratio taken after step 8 would be a ratio
   of gamma-encoded, contrast-curved, 8-bit values, which near white hold a
   stop of scene in a handful of codes. Beside steps 7-12 rather than in
   them, because every one of them is either the identity on the ratio or
   outside its reach:
   - [local tone] runs before both shoulders, so the ratio is read after
     it: the branch builds the develop's grid (every 2x2 cell's luminance,
     as `BilateralGrid.fromFrame`) and scales each block by its gain. It is
     not the identity above the knee: its compression about the mean pulls
     a bright neighbourhood down, and a map that left it out read 0.1-0.3
     stops too bright on the desk (median over the highlight blocks of the
     dog, the street and the backlit tower).
   - 7a is hue-preserving: both shoulders scale R, G, B by one factor of m,
     so the renditions differ by one factor per pixel and the map is
     single-channel by construction. 7b mixes toward the peak by an amount
     keyed on the scene luminance, the same in both, and a mix commutes
     with a scale. 7c takes a clipped pixel to white in SDR; its ratio is
     white / 1 and the HDR rendition is the clip at `white` times SDR white.
   - 8 and 8a act on the SDR picture the platform multiplies; the HDR
     rendition is that picture times the ratio, in linear light.
   - 9-11 are local and luminance-preserving to within their own
     tolerances; the map is a quarter of the picture's size and does not
     resolve them.
   - 12, and the framing crop and the turn after the develop, move pixels:
     each gain-map pixel is carried back through the turn, the framing,
     the inset and the lens model (`HdrGainMap.Placement`) to the sensor
     block it was measured on.
   Why identity past the knee and not a curve that fills the headroom: the
   develop holds white = gain at or under 4, so the scene's clip is always
   within P and the relaxed shoulder has nothing to compress. Stretching
   the knee-to-clip range to reach P regardless would put a slope over
   one at the knee -- a crease in every gradient that crosses it -- and a
   contrast the scene did not have. The HDR rendition of a daylight frame
   (gain about two) therefore reaches one stop over SDR white at the clip;
   of a night frame (gain four), two. The ratio is floored at one: with a
   white under nominal the SDR shoulder expands, and the HDR rendition is
   not made darker than the picture.
   Measured from the merged CFA, not the picture: each 4x4 block's sites
   through steps 1, 3 and 4 (each site's samples averaged with its largest
   left out, so a hot pixel at the clip cannot read as a highlight), then
   6 with the develop's gain, 6a, and the local operator at the block's
   centre. Step 2 and the demosaic are
   approximated by the block mean; the shading is read at the block
   centre. `HdrGainMapTest` holds the ratio, the 2-stop clamp, the
   placement through each turn and the SDR picture unchanged;
   `UltraHdrBench` writes the files on the desk.
  |
  v
7. Tone curve (global, hue-preserving), on the scene-linear values
   Three stages applied in order (the contrast curve that completes the
   tone is step 8a, after the gamma; until 20 October this list numbered it
   7c and the clipped-neutral recovery 7d, though the recovery ran first):
   a. Highlight roll-off: compress the *max channel* and scale all three by the
      same factor — `R' = R · T(m)/m` with `m = max(R,G,B)`. This preserves hue
      *and* saturation through the compression: no orange highlight shift, and
      a saturated red at three times nominal white comes out a saturated red at
      the top of the range with its gradation intact. (Keying the compression
      on luminance instead, `R' = R · T(Y)/Y`, leaves such a red out of gamut;
      the clip that follows flattens its gradation to a single value and, on
      other hues, rotates them. The max-channel key is the one that needs no
      clip.)
      `T` is the **filmic shoulder** (`ToneCurve.shoulder`, `Tone.h`
      `shoulderCurveParts`): linear to the knee (0.7 of nominal white), then
      `knee + h · ln(1 + a·u) / ln(1 + a·u_w)` with `u = (m − knee)/h`,
      `h = 1 − knee`, `u_w = (white − knee)/h`, and `a` the root of
      `a = ln(1 + a·u_w)` so the slope at the knee is one. A logarithm
      spends the headroom evenly in stops. The exponential it replaced,
      `knee + h(1 − e^{−u})`, had given up nine tenths of its headroom one
      stop over the knee and was white to the last place two stops over —
      every cloud brighter than that rendered the same, the sixth field
      test's chalk.
      **The white point is where the sensor stops**, not a constant
      (`DevelopParams.withGain`, `Tone.h` `resolveShoulder`; every develop
      calls it once its gain is known): `white = gain`, the sensor's clip
      after the develop's exposure gain, held in [0.5, 4], with the knee
      kept under 0.8 of it. A clipped pixel is therefore display white and
      everything under it keeps its tone. For one field test the white was
      a constant of five times nominal, so a cloud's tones stayed apart out
      to the clip; it did that, and it rendered the clip itself — a stop or
      two over nominal after the gain — at 0.86 of white, and everything
      bright with it: grey skies, dull sunlit walls, the eighth field
      test's haze. With the standing bias the gain is about two and the
      stops above the knee land at 0.82, 0.87, 0.92, 1.0 at the clip.
      Above the white point the curve is 1; the desaturation of stage (b)
      has already taken such a pixel to neutral. Both native develops
      tabulate the same curve (`ShoulderLut`, knee to white, interpolated),
      and `ToneCurveTest` holds the shape: C¹ at the knee, even increments
      per stop, white at the white point, monotonic throughout;
      `RawDeveloperTest` holds the white to the gain.
   b. Highlight desaturation, keyed on **luminance**: `Y = 0.2126R + 0.7152G +
      0.0722B`, measured on the scene-linear values before compression; a
      pixel whose Y exceeds `desaturationStart` blends toward neutral by
      `((1 − start/Y)² · highlightDesaturation)`. It was keyed on the max
      channel, and that rendered the fifth field test's red flowers pale pink:
      R at 3× nominal read as "three times overexposed" and was pulled most of
      the way to white, though the pixel's luminance was under white. Keyed on
      Y, only a colour that is bright *as a colour* — a specular, the sun, a
      near-white — gives up colour, and a saturated red keeps its hue and
      saturation. `ToneCurveTest` renders the flower and holds it red; the
      native `Tone.h` mirrors the key.
   c. Sensor-clipped neutral recovery, after the linear tone chain and before
      gamma/contrast (`SensorSaturation.kt`, `Tone.h`): read a sliding 2×2 CFA
      cell containing R/Gr/Gb/B, or the three unbalanced canvas channels. With
      per-channel black subtraction, let m = min((raw − black)/(white − black)).
      Let S(x) = smoothstep(clamp((x − 0.70)/0.30, 0, 1)). For all-channel
      clipping the weight is S(m). Also detect near-white partial clipping:
      q = min(normalisedRaw * WB), p = max(normalisedRaw), and
      C(p) = smoothstep(clamp((p − 0.90)/0.10, 0, 1)). Use
      w = max(S(m), C(p) * S(q)), then C' = C + w(1 − C) for each rendered
      linear colour channel. No shading, exposure or matrix participates in
      the sensor evidence. White balance cannot trigger clipping by itself;
      the second path requires a sensor channel near its physical ceiling.
      A September tower cell measured B=.656, G=1, G=1, R=.743 of range:
      with WB R=2.008 and B=1.513, all balanced channels approach white even
      though only green clips. An all-channel-only mask left it pink.
      Requiring bright companion channels protects single-channel-clipped
      spectral flowers. Applying recovery after linear tone prevents the
      matrix/local tone from recolouring or dimming the white. This does not
      reconstruct saturated detail. Mirrored in CFA and 2× Kotlin/native paths.
  |
  v
8. sRGB gamma encode
   For each channel:
     if linear <= 0.0031308: sRGB = linear * 12.92
     else: sRGB = 1.055 * linear^(1/2.4) - 0.055
   Via 4096-entry LUT for speed.
  |
  v
8a. Contrast: S-curve on the gamma-encoded value (`ToneCurve.renderDisplay`,
   `Tone.h` display table), with the toe. Slope at mid-grey =
   1 + 0.875 * contrast. After the gamma because the curve is a perceptual
   one: its pivot is mid-grey as displayed.
  |
  v
9. Chrominance smoothing (`ChromaSmooth.kt`, `Chroma.h`)
   Read-back support is regularised: for chroma C, return
   C + amount * (weightedSum − C * sumW) / max(sumW, 0.01).
   This is the original weighted mean at normal support, fading toward the
   pixel's own chroma when support vanishes. The former hard cutoff at 1e-6
   let a one-code luma difference create a 31-code chroma jump in the September
   ISO 918 fixture; the continuous fade protects isolated edges and parity.
   A joint bilateral on Cb = B − Y and Cr = R − Y, guided by luminance, at
   half resolution. The picture's Y, Cb, Cr are box-averaged 2×2; on that,
   two separable passes (rows, then columns) of 5 taps each replace each
   colour difference by its weighted mean, the weight of a tap
   `max(0, 1 − (ΔY/2.5σ)²)²` with σ = 12 codes (widening to 24 by ISO 3200) —
   a clamped quadratic that tracks a Gaussian of that width and is exactly
   zero past 2.5σ, so nothing crosses a brightness edge; then each
   full-resolution pixel reads the smoothed chroma back from the four
   half-pixels around it, weighted by bilinear distance *and* luminance
   likeness (a pixel with no like-brightness tap keeps its own chroma). Y is
   written back exactly, at full resolution, so no brightness moves and no
   luminance detail is lost; only the coloured salt-and-pepper of a high-ISO
   frame — which the eye reads as damage where it reads luminance grain as
   texture — is averaged away. Half resolution because the eye's colour
   acuity is a fraction of its brightness acuity (every JPEG here is 4:2:0
   already) and because the full-resolution version cost 380 ms of a
   12.5-megapixel develop; this one costs about 200, NEON over four pixels.
   Here, in the gamma domain where the speckle is perceptually uniform, and
   before anything reads chroma: the defringe judges chroma outliers, and
   the sharpen must not turn speckle into static. Every raw converter does
   this by default; the pipeline did not until the fifth field test's ISO
   3200 telephoto tiles came back as confetti. Both native develops mirror
   the Kotlin reference within two codes (`ChromaSmoothParityTest`, worst 1).
  |
  v
10. Defringe (lateral CA suppression)
    Per-pixel: if a pixel is at an edge AND its chroma is an outlier among
    same-luminance neighbors, desaturate toward the local mean.
    Does not correct longitudinal CA (the radial warp of §2 does the lateral
    part it can).
    Before the sharpen, not after it as an earlier draft of this list had it:
    sharpening a fringe makes it worse, so the fringe is taken out first.
  |
  v
11. Capture sharpening
    Unsharp mask on luminance channel only: a 3×3 box, the detail above a
    threshold added back to all three channels equally. Restores acutance
    lost to CFA sampling and demosaic. This is a restoration, not an effect —
    and it is *noise-adaptive*: the amount fades from full at ISO 800 to
    nothing at ISO 3200 (`DevelopParams.adaptedTo`), because on a frame at
    that gain what it would restore is grain, and sharpened grain is static.
    Parameters: amount (default 0.45; 0.55 until §3.8's calibration found
    it beginning to draw the edge at 100% on brickwork), threshold (default
    3.5 code values), maxShift (20, from 28).
  |
  v
11a. Simulated background blur -- Labs only, off by default, present only
    with a person model bundled (`PortraitBlur`, §3.15). Outside the person
    mask each pixel becomes a near-Gaussian mean of its measured neighbours
    (three box passes, radius width/150, computed at a quarter size and
    sampled back bilinearly); inside it the pixel is untouched; the mask's
    soft edge mixes the two. Position: after the sharpen, so the blur does
    not soften a sharpened halo into the subject's edge, and before the
    optics, because the mask was found on the sensor-oriented frame the
    optics then remaps. Every output is a weighted mean of measured pixels,
    but the blur is not the lens's (it does not grow with distance from the
    focal plane), so the photograph says *simulated* in its report and its
    Content Credentials (`c2pa.filtered`). The HDR rendition (6b) is not
    written for such a photograph: its gain map was measured from the
    unblurred CFA.
  |
  v
12. Fused optics gather (distortion and lateral CA), then valid-region crop
  |
  v
Output: 8-bit sRGB ARGB int array -> JPEG via libjpeg-turbo, or with the
        6b gain map -> Ultra HDR JPEG via the platform's encoder
```

**The optics, and the border.** The geometric corrections -- Brown–Conrady
undistortion and the lateral aberration rescale -- run as one fused gather
on the sRGB buffer after step 11 (`Optics.kt`, `Optics.cpp`; the
compromise of running them there rather than after step 5 is stated on
`Optics`). A gather that expands the field reads from beyond the frame
along part of every edge, and a channel rescaled for aberration reads a
few pixels further. There is no honest pixel to put there. The sixth field
test's answer was to repeat the edge row, which made a band of ribs; the
seventh's was to mirror the frame about its edge, which made a reflection
-- a strip of the eighth field test's flower, reversed, along its top. The
answer is the picture without that strip: the output is **cropped** to
the pixels the correction could read from the source
(`LensDistortion.validInset`: each side's inset grows by the worst
overshoot its edge's samples show until none falls outside, plus the
aberration's reach at the corner and one pixel for the bilinear neighbour;
capped at a tenth of the frame), and `Optics.applyCropped` writes that
window -- `Optics.cpp` takes the window's offset, and the reference the
same. The 24mm main lens loses about sixty pixels a side of 4080; the
JPEG is a little smaller than the raw, as most cameras' are. The mirror
read remains for the bilinear neighbour at the very edge and for callers
that correct in place (`LensDistortion.reflectIntoFrame`). A mosaic tile
is cropped the same way and its placement moved by the inset, so
registration keeps working in whole-frame coordinates (§3.4.4).
`LensDistortionTest` holds the inset minimal and the window's pixels
identical to the whole frame's; `OpticsParityTest` holds the two
implementations together.

**Future insertions (marked by phase):**

- **After step 5, before step 6 (Phase 2):** Geometric lens distortion
  correction (Brown-Conrady undistortion) and lateral CA correction (per-channel
  radial warp) -- currently after step 11, see above.
- **Replacing step 5 (Phase 3):** Sub-pixel kernel accumulation, which produces
  full colour at 2x resolution and removes the demosaic entirely for bursts that
  take that path. See §3.6, and §3.6.5 for what the chain becomes.
- **Before step 7 (built):** Bilateral-grid local tone mapping, §3.5: the
  pixel scaled by its neighbourhood's lift after step 6a and the exposure
  gain, before the roll-off.
- **Step 7c (built, 19 September):** sensor-evidenced recovery replaces the
  proposed near-clip WB fade. Fading WB alone cannot guarantee neutral after
  a non-neutral matrix and local tone; recovery therefore follows linear tone.

### 3.2 Merge Accumulation Mathematics

For a burst of N frames, one is designated the reference: the newest --
the frame nearest the shutter, the exposure the photographer saw -- or,
under dual conversion gain, the lowest-ISO frame, which keeps the
highlights the high frame clipped. (It was the lowest-ISO frame always,
on the reasoning that with one ISO they were all the same. They are not
when the meter moves inside the burst: the lowest was then the darkest
frame of a re-settling meter, and the whole shot, the DNG in particular,
came out at that frame's brightness.) For each pixel position (x, y):

```
reference value:  r = frame_ref[x, y]

For each alt frame i (i = 1..N-1):
  aligned value:  a_i = frame_i[x + dx_i, y + dy_i]    (from alignment field)
  gainScale_i   = (ISO_i · t_i) / (ISO_ref · t_ref)     (the light gathered; `FrameLight`,
                                                         each frame's own result, on the
                                                         ZSL and the sequential burst; 1
                                                         where either is unknown)
  normalized:     n_i = black + (a_i - black) / gainScale_i
  difference:     d_i = |n_i - r|
  noise envelope: e = noiseTolerance * sigma(r)         (sigma per brightness bin)
  weight:         w_i = 1                               d_i <= e
                  w_i = (4e^2 - d_i^2) / (3 d_i^2)      e < d_i < 2e
                  w_i = 0                               d_i >= 2e

Accumulation:
  sum_weighted = r + sum(w_i * n_i)
  sum_weights  = 1 + sum(w_i)
  result[x, y] = sum_weighted / sum_weights

Mean contribution: sum(w_i) / (N - 1)
  This is the "kept %" shown to the user.
```

**The weight has finite support.** A sample inside the noise envelope is
noise and keeps full weight; past twice the envelope it is motion and gets
none; between, `(4e² − d²)/(3d²)` falls from one at `d = e` to zero at
`d = 2e`, continuous at both ends. It was `exp(−(d/σ)²)`, whose tail is
never zero: a silhouette that had moved kept a small weight in every
alternate, and over a long burst those weights could accumulate into the
silhouette where it had been. `sigma` is measured
from the burst, per brightness bin -- the median absolute difference of the
first alternate against the reference, which in a static scene is noise
and is robust to what genuinely moved, converted to one frame's sigma
(`BayerMerger.estimateNoise`, the same in `bayer_merge.cpp`). The rule is
one expression, `d² <= e² ? 1 : d² < 4e² ? (4e² − d²)/(3d²) : 0`, in the
Kotlin merge (`BayerMerger`), the scalar and NEON loops of
`bayer_merge.cpp`, and the sub-pixel accumulation (`SubPixelAccumulator`,
`SubPixel.cpp`), whose envelope also carries the interpolation's own
uncertainty (§3.6.1). `MergeParityTest` holds native to Kotlin through it.

**Invariants:**

- When `gainScale = 1.0` for all frames, the merge is identical to the
  pre-DCG merge. The A/A null test is the proof. (The pedestal term is
  then exactly zero and the multiply-add is a multiply by one.)
- **The gain scales the light, not the pedestal.** The black level is the
  same code at every ISO. Dividing the raw code by the gain divides the
  pedestal too and drags the frame under black by `black · (1 − 1/g)`;
  over a burst whose gain moved, that put the merge below the black the
  develop subtracts -- shadows crushed to nothing, and the midtones green,
  since the white balance multiplies red and blue's shortfall by their
  gains. The eighth field test's green pictures were this, on bursts
  whose meter was swinging (§3.7). `BayerMergerTest`, "a burst whose gain
  moved merges to the reference's black, not under it"; `MergeParityTest`
  holds native to Kotlin at gains of one, four and mixed, on frames
  scaled the way a sensor scales them.
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
  4. At level 0 only: sub-pixel refinement of the winner by
     inverse-compositional Lucas-Kanade over every pixel of the tile
     (below). Bounded to one proxy pixel either way of the winner; zero
     where the fit declines.

Output: AlignmentField(tilesX, tilesY, dx[], dy[], fracDx[], fracDy[])
  dx, dy      integer winners, proxy pixels
  fracDx, dy  refinement, proxy pixels, in [-1, 1], zero where not refined
```

**The refinement: inverse-compositional Lucas-Kanade (`Aligner.refineSubPixel`,
`Align.cpp refineSubPixel`).** The template T is the reference tile on the
proxy, the image I the alternate, and the translation p is sought with
I(x + p) = T(x), minimising sum (I(x + p) - T(x))^2. Inverse compositional
(Baker & Matthews, 2004) linearises T rather than I, so the gradient and the
Gauss-Newton Hessian belong to the reference and are computed once per tile:

```
g(x) = (T(x+1,y) - T(x-1,y),  T(x,y+1) - T(x,y-1))      = 2 grad T
H    = sum g g^T                                         (2x2)
p0   = (dx, dy), the integer winner
repeat up to 3 times, until both |dp| < 1/1024:
    e(x) = I(x + p) - T(x)          I bilinear at the tile's one fraction
    dp   = 2 H^-1 sum g e
    p    = p - dp                    the inverse composition of a translation
fracDx, fracDy = p - (dx, dy)
```

The sign: with I(y) = T(y - s), linearising at p gives e = grad T . (p - s),
so the least-squares step is p - s and p - dp = s. Two iterations settle a
fraction to a hundredth on the bench's tiles; the third is margin.

*Fixed point, so Kotlin and NEON agree exactly.* The warp's fraction is the
same at every pixel of a tile, so its bilinear weights are four numbers; they
are rounded to 1/256, the interpolated value is kept in 1/256 of a code, and
H, sum g e and sum e^2 are exact integers (int64; a NEON lane holds at most
sixteen products of |g| <= 255 and |e| <= 65280 before it is widened). The
lanes add in their own order and the integers do not care. The 2x2 solve is
the only floating point, in double and written alike on both sides. The
rounding moves the warp by at most 1/512 pixel, and that is all it can leave in
the answer, since each step is measured from where the warp actually was.

*An edge* -- lambda_min(H) / 4N < 0.25 (codes/pixel)^2, or lambda_max more
than 16 lambda_min -- determines its translation across itself and nothing
along it. Each step is then taken along the strong eigenvector u alone,
`dp = 2 u (u . sum g e) / lambda_max`, the least-squares step of least norm,
and the place along the edge is left where it started. (A slanted edge's
staircase gives the weak axis a little energy; a two-axis step on that little
runs along the edge out of reach. Hence the anisotropy bound.)

*When it declines* (zero on both axes; `withFlatTilesBorrowed` then gives the
tile its fitted neighbours' median displacement for the 2x canvas, carried
from the tile's own integer):

- lambda_max(H) / 4N < 0.25 (codes/pixel)^2: a flat tile;
- |p - (dx, dy)| > 1 on either axis after any step: the integer search was
  wrong or the fit diverged, and neither is a fraction;
- lambda / 4N < s^2, s^2 the mean squared residual of the last iteration and
  lambda the solved axis's eigenvalue (lambda_min, or lambda_max for an edge;
  a two-axis fit that fails on lambda_min but passes on lambda_max keeps the
  part of its answer along u).
  The estimate's standard error in the weaker direction is
  sqrt(4 s^2 / lambda_min) = 1 / sqrt(N * ratio), so the test bounds it by
  1 / sqrt(N), 0.03 pixels on a 32-pixel tile. Its purpose is the tile of pure
  noise, which passes the other two and pins its fraction near zero wherever
  the frame moved; declined, it borrows. Byte-identical frames have zero
  residual and pass with a fraction of exactly zero.

Cost: the integer search samples every other pixel of a level-0 tile; the
refinement reads every pixel up to three times, through the NEON warp in
`Align.cpp`, and the consensus refits only edges and tiles that disagree. The
four extra SAD evaluations of the three-point fit it replaced are gone.

**The frame's motion (`Aligner.consensus`).** The integer search minimises a
SAD over every other pixel, and two kinds of tile defeat it however good the
fraction after it: a straight edge, along which every offset costs the same,
and a periodic texture, which matches itself a period away. On the desk's
chart it put the slanted edges eight to twenty pixels along themselves and
parts of the Siemens star one to five pixels off; with every fraction exact the
2x canvas still lost to enlargement there, and replacing those tiles alone with
the truth closed the gap. So, after the whole field is refined:

```
start from the tiles fitted on both axes within 4 proxy px of their
    median translation (a photograph has tiles the search lost by a
    hundred pixels; a least-squares start put a tripod burst's motion at
    sixteen)
fit total displacement = a + B (centre - mean centre)          (affine, 6 params)
    by least squares, refitted twice without tiles > 1 proxy px from the fit;
    fewer than a quarter of the two-axis tiles left: no frame motion, stop
for each tile that is > 0.5 proxy px from the fit, or was fitted as an edge:
    refine again from the fit's prediction itself (not its rounding)
    keep it if it fits at all, unless the tile's own fit was on both axes
    and its residual at least a quarter lower (a subject that moved)
```

dx and dy are never changed -- the CFA merge reads only them -- so a re-seeded
tile's displacement is carried in its fraction, which may then exceed a pixel.
An edge's own fit gets no say against the frame's, because its residual cannot
rank places along it: the proxy is a 2x2 bin with no other filter, a sharp edge
aliases there into a staircase, and an integer slide along the edge
reproduces the staircase exactly where the true, fractional place has to
interpolate it -- on the bench's edges the slid winner fitted ten times better
than the truth. The lean toward the frame is deliberate: a moving subject
placed by the frame's motion disagrees with the reference and the 2x canvas's
robustness weight rejects it, whereas an edge slid along itself agrees
everywhere and is kept, its correct values twenty pixels from where they
belong, and the rows they left reconstructed from fewer frames' phases -- an
edge rendered as soft as a tripod burst's, which is what the bench measured.

**Why it replaced the three-point fit.** The fit described next read the
fraction from the SAD at the winner and its four neighbours, sampled every
other pixel. On the desk's known-shift bench (`SuperResEvidenceBench`,
`work/sr-evidence-20261020/REPORT.md`) its median error was 0.32 sensor
pixels, against the 0.125 a 2x canvas cell needs, and on byte-identical frames
it reported offsets of up to 0.43: three samples of a cost curve that is not
symmetric about its minimum do not locate the minimum. With exact shifts the
same canvas beat enlargement; with these it lost.

**Why the equiangular fit and not the parabola (history).** Before the
Lucas-Kanade refinement, the fraction came from an equiangular fit through the
SAD at the winner and its two neighbours on each axis,
`t = (before - after) / (2 * (max(before, after) - at))`, bounded to ±0.5;
`Aligner.subPixelOffset` keeps it, with its tests. The first version fitted a
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

The luma proxy sums each complete2×2 CFA cell and subtracts the sum of its
R/G1/G2/B spatial black levels. That signal is divided by
`4 * sensor.range * gainScale` before byte encoding, so exposure differences
from ISO bracketing do not confuse SAD and unequal pedestals do not bias the
guide. Native Bayer placement still uses even sensor-pixel shifts.

Native (`Align.cpp`) and Kotlin (`Aligner.kt`) make every decision identically,
including tie-breaking order and which tiles the refinement declines.
`AlignerParityTest` (device) and `AlignerHostParityTest` (desk, opt-in
`-Pmf.host.lib`) hold them to zero differing tiles and a sub-pixel difference
under 1e-5; the refinement's integer sums make it zero in practice.

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
reduction in the dominant cost of alignment. The sub-pixel refinement is
unchanged and still starts from the winner.

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

The guided sweep registers each new proxy against neighbouring accepted
tiles, not against an assumed grid position. A neighbour can itself have an
erroneous attitude placement, so `MosaicAssembler` clusters the predicted
tile homographies by four-corner agreement (two 3-pixel proxy-fit residual
budgets), selects the group with the most verified inlier support, then
refits from only that group's weighted corner positions. Averaging all
pairwise predictions gave a 45-pixel placement error on the fixed DNG
overlap case when one of two matches came from a misplaced neighbour.

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

Before the seam cut or any pyramid level is accumulated, the new tile's
overlapping samples are compared with the existing canvas. Their median
luminance ratio gives one exposure gain for the whole tile; a scalar leaves
the locked white balance unchanged and the median resists a small moving
object. It is applied during full-resolution sampling and Gaussian-level
construction without copying the tile. This is Brown and Lowe's gain
compensation followed by multi-band blending: the broad exposure mismatch is
removed before the bands feather residual differences.

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
| 0 | 1 | the canvas as before | the seam cut (below); the ramp 24 px × hand-off weight is what is stored |
| 1 | 1/4 | `wide_[0]` (M_1), `narrow_[0]` (M_0 = the seam) | M_1: ramp 160 px |
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
missing: the tile whose centre is nearer owns the pixel. The first answer was
a weight that rises steeply with depth into the tile,
`ramp² × (edge / halfTile)^12`, so the deeper tile outweighs the shallower by
a large ratio everywhere but a narrow zone about the equidistant line -- a
soft Voronoi, a fifteenth of a 35% overlap wide. Inside that zone the two
tiles were still averaged, and the sixth field test saw the soft band along
every seam.

**The seam cut** (`MosaicCanvas::cutSeam`, `Seam` in Mosaic.h). The detail
band is now blended by *ownership*: every pixel of it comes from one tile.
Before a tile is written, a binary labelling of its footprint on the canvas
-- new tile or canvas -- is chosen by a minimum cut (Dinic's max-flow on a
grid at one node per 8 canvas px, some 70,000 nodes for a tile, tens of
milliseconds on the placement thread). A node the canvas does not cover is
the new tile's; a node outside the new tile is the canvas's; the rest are
free. Cutting between two neighbouring nodes costs their *disagreement*,
`8 · (|ΔY(p)| + |ΔY(q)|) + 4` with ΔY the mean luma difference between the
tile and the canvas over sixteen of each node's pixels (every other pixel
each way, read at pixel centres as the accumulation reads them, so a texture
at the pixel scale is measured rather than blurred between two pixels;
capped at 64 codes),
so the seam runs where the tiles agree -- a sky, a shadow, an edge that
registered -- and not across a texture they place a pixel apart. Giving a
node to the shallower tile costs `4 · |depth_new − depth_old|` in tile
pixels -- a seam displaced five nodes from the equidistant line to skirt a
texture the tiles place a pixel apart pays about what cutting through it
would, so it moves that far for a real disagreement and no further for
noise: the Voronoi line is where the seam falls when nothing in the picture
argues, and the deeper tile is the sharper one. The canvas's depth at a node is read
back from the weight it stores -- the hand-off wrote `(depth/halfTile)^12`,
so its twelfth root is the depth of whichever tile owns the pixel now -- and
a node inside the new tile's own 24 px ramp is given away wherever anything
else covers it. The labelling is read back bilinearly and steepened so the
transition spans `Seam::kFeatherPx` = 2.5 canvas pixels: the micro-feather.
In the accumulation, where the new tile owns a pixel its value replaces the
canvas's and its own hand-off weight is stored (so the next tile's cut reads
this tile's depth); where the canvas owns it the new tile writes nothing; in
the feather the two mix. `narrow_[0]`, the coarse level the detail band's
correction is made against, follows the same seam, so `A_0 − up(B_1)` is one
tile's detail everywhere. The coarser bands keep their wide ramps: a seam is
a decision about texture, and a step in exposure is still spread across the
overlap by `wide_[0]` and `wide_[1]`.

`MosaicBlendDeviceTest.theSeamKeepsTheOverlapsTextureAtOneTilesContrast`
holds the overlap's texture within 3% of a single tile's (it was 87% under
the hand-off); `theSeamRoutesAroundWhatTheTilesDisagreeOn` puts a bright
disc in one tile on the equidistant line and requires it whole or absent,
never a half-moon; the one-tile null below still holds to the byte, since a
tile that overlaps nothing has no free node and no seam.

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
under the hand-off a third of it was the zone where the two tiles still
mixed. With the seam cut the whole window is one tile's texture (see above).

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
   percent of a tile from exact and is recorded with zero inliers so later
   registrations weigh it accordingly.

**Then the canvas decides.** Either tier can be a hundred pixels or more out
on a 4080-pixel tile: the registration is fitted on a 320-pixel proxy, so a
proxy pixel of fit error is thirteen on the canvas, and the attitude drifts
and is read a frame from the exposure. The eighth field test's tower stood
three times in one sky, two of them translucent: two tiles placed a few
hundred pixels apart, the coarse bands averaging both. No blend hides a
misplacement of that size; what fixes it is the measurement the canvas
itself offers. Before a tile is drawn it is **refined against the canvas**
(`MosaicCanvas::RefineTile`, `MosaicSession.place`,
`MosaicPlacement.correctionFor`): where it overlaps tiles already drawn,
a luma grid of the overlap at 32 canvas pixels a cell -- both grids box
means over the same samples, so they carry the same frequencies -- is
searched for the tile's translation out to a quarter of a tile by
zero-mean normalised cross-correlation, each offset's correlation scored
against its own chance level (`2/√cells`) so a corner on a corner cannot
win with a handful of cells; then the overlap is cut into a 4×4 of blocks
at 4 pixels a cell, each searched ±8 cells around that answer, refined to
a fraction of a cell by parabola, and kept only where its peak stands
above the correlation two cells either side on both axes (a block on a
plain edge correlates anywhere along it). Each kept block is one
correspondence -- where the placement put its centre, where the canvas
found it -- and the correction is a translation (the blocks' weighted
median offset, outliers past 24 px dropped) or, from eight or more blocks
that it explains 20% better and without changing the tile's scale by more
than 3%, a homography. The correction is composed onto the placement, for
the pixels and for the record later registrations chain from; a tile that
overlaps nothing drawn, or nothing the two agree on (sky), lands as it
came. About 40 ms a tile. `MosaicRefineDeviceTest` places a tile a
hundred pixels off and a quarter of a tile off and has it back to within
two pixels; a tile placed right is left alone.

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

**The lock.** `ZslRawStream.lockForSweep` pins the sensor for the mosaic's
duration: exposure as a *manual* exposure -- the meter's converged ISO and
shutter, or the floored pair below -- rather than an AE lock, which is the
meter's promise rather than the sensor's setting; white balance locked; and
focus held at the distance the lens was at (`LENS_FOCUS_DISTANCE` from the
latest result, sent back as `CONTROL_AF_MODE_OFF` with that distance where
the lens accepts one, single-shot `CONTROL_AF_MODE_AUTO` with no trigger
where it does not). A continuous autofocus left running refocused tile by
tile as the reticle crossed the scene, and tiles focused at different
distances stitch at different scales. Every tile is shot at one focus and
one exposure; the anchor's colour and develop gain are held beside them.

**The shutter floor, and how it gives way in the dark.** What keeps 6°/s at
five pixels rather than forty is the exposure. Auto exposure on a dim scene
chooses 1/30 s without regard to the lens. When the mosaic locks the stream
and the converged exposure is longer than the lens's floor, the stream is
pinned at the floor with the ISO scaled to keep the light:

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
is the settle time of steady polls in a row, at least three of them: 150 ms
(from 250, then 170). It no longer stretches to cover the burst, because
the burst is no longer the ring's newest frames -- see the next paragraph.
The ring fills in proportion and the burst begins itself; there is nothing
to press.

**The burst is exposed after the lock, not before it.** For four field tests
the lock took the ring's newest frames, and the newest frames in a
zero-shutter-lag ring at the moment of the lock were exposed a frame or
three *before* it -- the raw frame reaches the ring some 60–100 ms after
its exposure -- while the hand was still arriving. The capture felt
instantaneous because it was: nothing was exposed for it, and the tiles
carried the approach's blur however still the hand was afterwards. The
lock now *begins* the burst (`MosaicCapture.Collecting`). Not a frame in the
ring is taken. The runner records the moment on the sensors' clock
(`SENSOR_TIMESTAMP` is on `elapsedRealtimeNanos` where
`SENSOR_INFO_TIMESTAMP_SOURCE` is realtime; elsewhere the newest frame's
stamp plus one) and waits for `tileFrames` ready frames whose exposure
began at or after it (`RawRing.readyCountSince`), polling at 33 ms with the
gate held through the wait: a gyroscope peak past the ceiling discards the
frames exposed during it and restarts the count; the reticle leaving the
radius gives the node back (`MosaicGuide.release`: pending again, no red,
no beat); `COLLECT_TIMEOUT_MILLIS` (2.5 s) without the frames rejects it
with "hold still for the burst". Then the newest `tileFrames` are
snapshotted -- they are the fresh ones, being the newest -- the node is
done, `CONFIRM` fires (after the last frame is exposed, so the motor cannot
reach it), and the log says how long after the lock the first frame was
exposed. The screen draws the exposure as the lock ring filling a second
time in `Confirm` ink (`MosaicGuideView.exposeProgress`, fresh over wanted)
with the hint `EXPOSING · HOLD`; a turn empties it and it fills again.
Five frames at 30 fps is 165 ms plus the delivery latency: some 250 ms the
hand holds for on purpose, which is the deliberate exposure the field test
asked to feel.

**The radius is the radius.** In and out at one lock radius, five percent
of a tile, for every node in every state; see the retired magnet above. A
tremor that carries the reticle over the edge restarts the hold, and the
hold is 150 ms, so it is not a costly restart.

**One target at a time.** The nodes are taken in a serpentine over the rows
(`MosaicGuide.order`: top-left, top-right, bottom-right, bottom-left on a
2×2), and only the `activeNode` -- the first in that order not yet taken --
can be approached or locked. The guidance draws it as the one target, with
its ring and, when it is off the reticle, an arrow from the reticle's edge
toward it; the nodes taken are dim checkmarks; the nodes to come are faint
dots. The capture sequence is: the ring fills over the hold (150 ms); the
ring fills again, in `Confirm`, as the fresh frames are exposed (about 250
ms); the burst is snapshotted, the node is done, `CONFIRM` fires; a pulse
expands from the reticle for `LATCH_MILLIS` (150 ms, driven by the view's
`latch` field the runner advances every poll); the arrow turns to the next
target.
Four identical dots asked the user which to go to, and a choice is a
hesitation.

**The preview's zoom.** The dots are computed on the sensor's frame, which a
digital zoom never crops; the preview is what it crops. A dot drawn without
the zoom moved slower than the scene behind it by the zoom factor -- a
target that slides. `MosaicGuidance` takes the stream's `zoom` and maps
sensor fractions through `0.5 + (f − 0.5)·z`; and Super Res resets the zoom
to one when selected and refuses the pinch while it is, so in practice the
factor is one. The preview is constrained to the raw stream's own aspect
(`ZslRawStream.previewSizeFor`, within 2%), so no letterbox scale enters.

**Frames per tile.** `MosaicCapture.tileFrames` holds the burst setting to
4–6 frames per tile: fewer is grain from a small telephoto sensor at high
ISO, more is a hold longer than a hand keeps. The merge is logged per tile
-- `merged N of M frames, mean contribution` -- and on the live device test
merges 4 of 4 at 0.95–0.97. Each frame's ring slot is released the moment
the merge has read it (`RingSnapshot.release`), so the ring refills under
the merge and the next node's fresh frames have somewhere to land; the
old rule that closing one snapshot freed every locked slot, which made the
guidance wait for the previous tile's merge before taking the next burst,
is gone (`RawRing.Unlock(slots)` is per slot).

**The mesh.** A tile's fine band is sampled bilinearly onto the canvas, and
a projected sample almost never lands on a tile pixel centre; where it does
it carries that pixel's whole noise, a quarter-pixel away it averages two or
four and carries a quarter of it. On a noisy tile that is a periodic
modulation of the grain's variance at the beat between the tile's grid and
the canvas's -- the fifth field test's "4K TV screen" over every dark tile.
`accumulateFull` now averages four bilinear samples a quarter-pixel either
side of the point (a half-pixel box prefilter), which makes the variance
nearly uniform; the detail is a quarter-pixel wider, which the sharpen
already paid.

**The cross-hatch.** The prefilter takes most of the mesh out, not all.
Where two tiles overlapped, each turned a little against the other by the
hand, two residual meshes at two orientations lay over each other in the
hand-off's mixing zone and the eye read the sum as weave -- the sixth field
test's cross-hatching across every shadowed overlap at ISO 3200. A *noise
floor* (the detail band scaled back in shadowed overlaps at high ISO) was
built for it and then retired in the same week: the seam cut of §3.4.3
takes every detail-band pixel from one tile, so there is no longer a zone
where two meshes are summed, and the floor had nothing left to do but soften
shadows.

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

*Region-aware share of the lift (Labs).* `DevelopParams.liftScale` scales
the ceiling of §3.5's shadow lift per grid cell -- bilinear at the slice's
own cell coordinates, the same read in `BilateralGrid.gainAt` and
`Tone.h liftScaleAt` (`DevelopParityTest.nativeAndKotlinRegionScaledLiftAgree`).
The scale is in [0.4, 1]: a region may decline lift the scene earned, never
take more, and the highlight term (the bright end) is not scaled. See §3.15
for where the scale comes from. Null, the default, is the operator below
unchanged.


**As built** (`Tone.h` `BilateralGrid`, `BilateralGrid.kt` as the reference,
in both develops behind `DevelopParams.localTone` and
`DevelopParams.shadowLift`; the `LOCAL TONE` control turns both on, at
**0.15** and **1.0 / 3.0 stops**, and off together). Two operators share
one grid:

- the **symmetric operator**, the base layer's range about the picture's
  mean brought in by the strength -- 0.15 since the calibration of 17
  September (§3.8), 0.5 before it, which compressed the scene's own light
  along with the raw's excess: a backlit tree as bright as its sky;
- the **shadow lift**, since the second calibration of the same day: a
  neighbourhood far enough under the scene's *bright end* is raised, by up
  to a ceiling the scene's *range* has earned. The tenth field test's
  backlit tower had swung from haze to silhouette -- the facade at seven
  stops under the develop's white, under the display curve's toe -- and a
  symmetric strength that would have lifted it three stops would have
  pulled the sky down by as much. The lift is one-sided and anchored on
  the bright end, so the sky does not move, and it is earned by range so a
  room or a portrait, whose two or three stops the display holds, renders
  exactly as the symmetric operator alone renders it.

```
Input: linear RGB after the colour matrix, the purity compression (§3.1
step 6a) and the exposure gain, before the global roll-off.
Luminance L = 0.2126 R + 0.7152 G + 0.0722 B.

Grid: cells `cell` px square, cell = max(16, min(width, height) / 24)
      (127 px on a 12 MP frame; 63 on the bench's half-size develop of it),
      16 bins over log2 L in [-10, +2] (0.8 stop each). Each cell: (sum of
      log2 L, count). Beside it a 240-bin histogram of every splatted log2 L.

Splat: one sample per 2x2 CFA block -- the block's red, the mean of its two
       greens, its blue, through the matrix, the purity compression and the
       gain -- into the cell at (x/cell, y/cell), shared between the two
       bins nearest its log2 L by distance. A quarter of the pixels, no
       demosaic; the grid is coarser than that by orders anyway.
Blur:  [1 4 6 4 1] / 16 along each of the three axes in turn.
Slice: per pixel, trilinear at (x/cell, y/cell, log2 L) -> base b.
Pivot: the count-weighted mean base of the whole grid.
Bright end E: the 92nd percentile of the histogram. Dark end D: the 10th.
Range R = E - D.

Ceiling C = min(shadowLiftMax, shadowLift * max(0, R - 3.3))     [stops]
Lift(b) = -localTone * (b - pivot)                                 (symmetric)
        + C * smoothstep((E - b - 0.5) / (2.0 * C))                (shadow)
Gain:  k = 2 ^ Lift(b);  R, G, B *= k.
```

The detail layer `log2 L - b` is carried through unchanged because every
pixel with the same base gets the same gain. The constants: **3.3 stops** is
the range the global chain renders readably on its own -- past it the dark
end is under the toe (`RANGE_HELD`); **0.5 stop** under the bright end
nothing moves, the midtones being the picture (`SHADOW_DEAD_ZONE`); the
smoothstep rises over **2.0 stops per stop of ceiling** (`SHADOW_RAMP_PER_
STOP`), which holds the lift's slope against the base under three quarters,
so two neighbourhoods on the ramp keep their order and a quarter of their
separation, and past the ramp all of it: the deepest shadows are lifted
together, not flattened into each other. The **cell** was 16 px; at that
scale the base resolved the tower's own arches and courses, and lifting the
base flattened them. At a twenty-fourth of the frame the base is the
illumination -- the tower as a whole against the sky as a whole -- and the
architecture is detail that rides through. (The bench's half-size develop
reads half the cell, so it judges the same neighbourhood as the phone.)

On the field test's six scenes (`DngAnalysisBench`): the backlit tower, the
street under cumulus and the tower block have ranges of 5.7 to 6.5 stops and
earn 2.4 to 3.0; the dog on the sofa (2.1) and the rose (2.7) earn nothing.
The tower's facade, seven stops under white, comes up 2.9 stops --
sRGB 50 to 70, arches and corbels readable -- and the sky's histogram is
unchanged (`DngDevelopBench`, `lift10m30` against `cal1`).

Properties, each a unit test (`BilateralGridTest`): a bright region comes
down and a dark one up under the symmetric operator, meeting at the pivot at
full strength; a pixel a fifth of a stop above its neighbours stays exactly
that far above them; a dark pixel four pixels from a hard bright edge gets
the dark side's gain within 8%, not a blend; a scene of ordinary range earns
no lift and renders as the symmetric operator alone; a six-stop scene earns
the ceiling, spends all of it on the dark side and none on the bright end;
the lift keeps the order of two neighbourhoods along the whole ramp. The
operator is hue-preserving because it is one scale per pixel. Native and
Kotlin agree within the develops' own bound of two codes
(`DevelopParityTest.nativeAndKotlinLocalToneAgreeAndAct`,
`nativeAndKotlinShadowLiftAgreeAndAct`,
`LinearDevelopParityTest.withTheLocalOperatorItAgrees`,
`withTheShadowLiftItAgrees`).

Known limits: a lifted region shows what the raw had. The telephoto's
10-bit raw put the tower at three codes over black, and three stops of lift
show the quantisation as coarse colour blotching the radius-2 chroma
smoothing cannot reach (the main sensor's 12-bit raw of the same scene is
clean); a coarser second chroma pass for lifted shadows is the likely
answer. Not applied to Super Res tiles: a base computed per tile differs at
every seam. The right grid for a mosaic is one over the canvas, which is
future work.

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

        normalised = black(site(s)) + (value(s) - black(site(s))) / gainScale(f)
        acc[c] (i,j)  += w * normalised
        wsum[c](i,j)  += w

Resolve, per cell, per channel:
    out[c] = acc[c] / wsum[c]       where wsum[c] > threshold
```

Three things carry over from §3.2 unchanged: gain normalisation, so a DCG burst
accumulates on one scale without scaling its CFA black pedestal (including
the empirical noise comparison); robustness weighting, so a sample that disagrees with
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
see §3.6.5 -- is what the user gets, with the reason in the readout, and
the status line says "saved at 1x" and why.

**A tile with no texture borrows its fraction.** The sub-pixel fit
(§3.3.1) declines a tile whose cost curve is flat -- sky, a wall -- and
that reads as a fraction of exactly zero: "this frame landed on the
reference's own sites". It did not; the hand moved the whole frame, and
the flat tile moved with the textured ones beside it. Left at zero, a sky
scene predicted a third of a tripod's holes and the canvas declined the
picture, or ran and left the sky's cells between the reference's sites
unfed; the eighth field test's 1-SHOT saved at 1x on every scene with a
sky in it and at 2x on the dog on the carpet. Before the prediction and
the accumulation (`ZslCapture.develop`, the 2x path only -- the ordinary
merge reads integer offsets and is unchanged), every tile without a fit
takes the median fraction of the fitted tiles among its eight neighbours,
or of the whole field where none of those fitted
(`AlignmentField.withFlatTilesBorrowed`). A field with no fitted tile at
all is a tripod and is left as it is, and declines as before.

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
tile is swept by every sensor row and column whose samples can reach it --
per *alignment* tile, its own extent plus that tile's own displacement, in
the same row-then-column order as ever -- and a tile's own cells are the
only ones it writes, so the tiles are bit-identical to the single sweep they
replaced. Per alignment tile, because the first version swept the frame-wide
reach for every accumulator tile: on a real burst, one alignment tile that
had wandered to the search's limit on a patch of blank wall set the reach
for the whole frame, and a sixteen-frame one-shot took 235 s to accumulate
where synthetic frames took three. Per tile an outlier costs only its own
area, and the same burst accumulates in 2.1 s
(`ZslStreamDeviceTest.aOneShotBurstRunsTheCanvasAndSaysWhatItDid`;
`SubPixelParityTest` holds the sample set and order to the bit). Measured
interleaved on one burst: full-width bands of 8 rows
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
  -> colour matrix -> purity -> [local tone] -> tone curve -> clip recovery
  -> sRGB gamma -> contrast -> chroma smooth -> defringe -> sharpen
  -> optics -> valid-region crop
```

becomes, as `LinearDevelop.cpp` (and its reference `LinearDeveloper.kt`)
runs it:

```
Reference CFA -> hot pixel                       <-- on the reference, before placement
  -> SUB-PIXEL ACCUMULATION (this section)       <-- replaces demosaic, outputs
                                                     16-bit RGB at 2x in sensor counts
  -> black -> shading -> white balance           <-- per channel, on the canvas
  -> exposure gain                               <-- measured on the canvas, held
                                                     near the meter's (§3.8)
  -> colour matrix -> purity compression (6a)
  -> [local tone, §3.5]                          <-- grid from the canvas's 2x2 means
  -> tone curve (7a, 7b) -> clipped-neutral recovery (7c, from the canvas's
                                                     unbalanced channels)
  -> sRGB gamma -> contrast (8a)
  -> chroma smooth (9) -> sharpen (11)           <-- no defringe, see below
  -> render crop (the zoom's framing)            <-- at the encode; no optics, see below
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

**The optics correction and its crop (§3.1 step 12) are not run, and the 2x
JPEG carries the lens's distortion and lateral aberration uncorrected.** Its
1x sibling, and the 1x JPEG written when the canvas declines, are corrected
and cropped, so the two can differ in geometry by the lens's peak
displacement at the edge -- the 24mm main lens's correction moves its edges
far enough to cost about sixty pixels a side of 4080 in the crop. It is
omitted rather than added because it cannot be added honestly here yet:

- *No lens data reaches it.* `SuperResCapture.finishOutputs` is given the
  `Lens` for the EXIF, not the `LensProfile`, and the canvas has no
  aberration estimate of its own; the 1x path measures one on the developed
  bitmap.
- *Its geometry is the sensor's doubled.* The Brown–Conrady intrinsics are in
  active-array pixels; on the canvas the principal point and focal length
  are doubled and offset by the half-cell of the 2x grid, and the render
  crop that frames the zoom has to be placed before the inset and carried
  through it, as the 1x path does (`RawBurstCapture.finishOutputs`).
- *The memory and the gather.* The developed canvas is 150 MB of three-byte
  RGB in an anonymous mapping, and a gather cannot run in place: it needs a
  second mapping the size of the cropped output while the first is live,
  doubling the develop's peak at the moment the ring may still hold frames.
  `Optics.cpp` gathers on ARGB bitmaps; the canvas needs a three-byte variant,
  banded so it can release its source as it goes, held to the Kotlin
  reference by an `OpticsParityTest` of its own.

That is device work: the peak has to be measured on the phone, with the ring
full, before it ships. Until then the 2x path is the one place a lens's
geometry reaches the JPEG, and §3.6.6 has no row for it.

**The develop is native, from the canvas's pages to the file.** A 50 MP canvas
developed to RGBA is 200 MB, which cannot be a Bitmap beside a 256 MB heap
already holding a ring of raw frames. `LinearDevelop.cpp` develops the canvas
in strips into three-byte RGB in a second anonymous mapping, releasing each
strip of the canvas with `madvise(MADV_DONTNEED)` as it is consumed; sharpens
that in place with the §3.1 operator on band-local luma, snapshotting every
band's two outside halo rows before any worker can write; and hands it to the
strip JPEG encoder (`Jpeg.h`), which writes to the descriptor `ImageSaver`
opened. The pixels are written in the sensor's orientation and the EXIF
orientation tag says which way is up, because turning fifty megapixels is a
second copy of them. Everything from the colour matrix onward is `Tone.h`, the
same functions the CFA develop runs, so a 2x photograph and its ordinary
sibling render the same tone; `LinearDeveloper.kt` is the Kotlin reference and
`LinearDevelopParityTest` holds the native path to it within two codes, one
with the sharpen off. On the full-size synthetic burst the develop took 850 ms,
the sharpen 150 ms and the encode 220 ms.

**The DNG beside it is the ordinary merge's.** A DNG is the sensor's CFA,
and the canvas is not that. So the 2x path
runs the ordinary merge as well -- the same frames placed by the same
alignment fields, accumulated the §3.2 way -- and writes its DNG under the
same name as the 2x JPEG. That DNG is a raw file that is true, and it is also
what is developed when the canvas declines at any of its three gates
(prediction, allocation, measurement), so a decline costs the user nothing but
the resolution they asked for. The pair carry one name and one gallery entry.

**This does not replace the existing merge.** A single frame, a burst without
the diversity, a device short of memory, or a user who has not asked all take
the §3.2 path. The 2x path is asked for as **`1-SHOT`**, the first preset of
the Super Res pill (`MosaicPreset.ONE_SHOT`; offered only where
`CameraCapabilities.supportsSuperRes` holds; the `2X` set-once toggle it
absorbed is gone): a shutter tap in Super Res with it selected takes
`MosaicPreset.ONE_SHOT_FRAMES` = 16 frames from the ring on whatever lens
is up -- the one-shot is for any distance, and the lens strip stays offered
for it -- and sends them through the same develop queue as any shot with
the canvas asked for (§3.7). Sixteen because §3.6.3 measured an eight-frame
handheld burst leaving a fifth of the chroma cells to the fill, and at
30 fps sixteen is half a second the hand is holding anyway. Which path ran
is recorded in the readout as `· 2X` or `· 2X declined`, in the file name
as `_sr2x_`, in the EXIF description as "accumulated at 2x", and in the
gallery as a `SUPER-RES 2X` badge -- because a 2x output is a different
claim about the photograph and should say so everywhere the photograph is.

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

### 3.7 The Shutter: Zero Lag, and the Develop Behind the Viewfinder

**Two calls.** `ZslCapture.take(stream, frames)` is the shutter: it locks
the newest frames in the ring (`RawRing.lockNewest`, a mutex and a scan of
at most 32 entries) and returns, in microseconds, on the thread that
pressed the button. The frames were exposed before the press; there is
nothing to wait for. `ZslCapture.develop(shot)` is everything after -- the
merge, the render, the write -- and the screen runs it on a worker, one
shot at a time in the order taken (`CameraScreen`'s `developQueue`, a
`Channel<PendingShot>` consumed on `Dispatchers.Default` under
`NonCancellable`: a shot in hand is a photograph owed). The viewfinder is
never paused, because it never was -- the preview is a separate output --
and the shutter is never *busy* for a ZSL shot: `busy` is the sequential
camera's and the self-timer's. The haptic fires after the lock, so the
frames it could blur are already in the ring's hand. `captureAndMerge` is
the two in one call, for a caller that wants to wait (the device tests).

**The ring is finite, and the shutter says so.** A press whose frames the
ring cannot hold (`ZslRawStream.lockedFrames() + frames > ringCapacity`)
is refused with the reject haptic and a status line, and a press that
finds fewer ready frames than it wants -- the last press took them a
moment ago -- waits for the camera to expose them, up to the frames' own
time plus 150 ms, rather than taking a short burst: the eighth field test
spammed the shutter into ten-frame shots that held twenty slots between
them and then two-frame and one-frame shots from what was left. Under
`MIN_BURST_FRAMES` = 4 after the wait the press is refused; the haptic
fires only once the frames are locked. One press waits at a time.

**The ring under a develop.** Eight of twelve slots locked for the half
second a merge takes would leave the next press two frames. So the merge
gives each frame's slot back the moment it has read it
(`RingSnapshot.release(i)` → `RawRing.Unlock(slots, n)`, per slot; the
reference's after `setReference` copied it), and the camera refills under
the merge: a press half a second later finds a full burst, a press a
tenth of a second later finds four or five
(`ZslStreamDeviceTest.backToBackShotsBothDevelopAndTheRingRefillsUnderTheFirst`).
Two snapshots hold their own slots; closing one frees none of the
other's (`RawRingTest.twoSnapshotsHoldTheirOwnSlots`). Under the 2× canvas
the slots are held to the end, because the canvas reads every frame again
once the burst is aligned. And the ring's mapping outlives its close while
a snapshot holds slots -- the lens switched under a developing shot closes
the stream, and freeing the pages under the merge would take the process
down -- the last release destroys it
(`RawRingTest.closingTheRingWithASnapshotHeldKeepsTheMappingUntilItIsReleased`).

**How many frames.** AUTO takes the burst setting up to
`CaptureModes.AUTO_MAX_FRAMES` = 10 however deep the ring: signal-to-noise
grows as √N, so twenty frames over ten is half a stop for a burst window
twice as long -- twice the hand's drift for the alignment to chase, twice
the chance the subject moved. NIGHT takes what its plan from the hand
allows (below), or, with no gyroscope, what the ring has. ACTION takes
four. AUTO that the histogram resolves to night (a dark scene) takes
night's guard -- none -- but AUTO's ten frames and AUTO's cap: the mode the
user chose says whose hand is holding the phone.

**NIGHT's exposure, from the hand** (`MotionClass.kt`, `NightPlanner.kt`).
NIGHT used to take the ring's ceiling of the meter's own frames under a
nominal 250 ms cap (`CaptureModes.NIGHT_EXPOSURE_NS`) that the repeating
request never reached: a tripod shot the same thirtieth of a second as a
hand. Now, while NIGHT is selected, the gyroscope (`MotionSensor`, at the
platform's fastest rate) is read every 100 ms:

```
rate   = RMS over the last 500 ms of the angular-rate vector, averaged in
         20 ms bins (25 Hz noise bandwidth; a 10 Hz tremor passes at 94%),
         the gyroscope's noise (0.0008 rad/s, a datasheet estimate)
         removed in quadrature                          MotionMeter.rmsRate
class  = TRIPOD   if rate <= 0.75 px / (f_px · 1/2 s)
         BRACED   if rate <= 0.75 px / (f_px · 1/4 s)
         HANDHELD otherwise                             MotionClassifier
```

`f_px` is the lens's focal length in pixels (`LensProfile.pixelsPerRadian`:
the intrinsic calibration, else the focal length over the sensor width,
else the 35mm equivalent). The budget is the newest frame's, because the
newest frame is the merge's reference and its blur is the photograph's: a
class is the rate at which the hand holds that class's *shortest* exposure
under 0.75 px. On a 3000-pixel main lens the boundaries are 0.06 and
0.03 degree a second. The class has hysteresis: it falls to a less stable
one at once when the rate passes the boundary by 1.25×, and rises only
after the rate has been under 0.8× the boundary for 300 ms; it starts
HANDHELD. The plan is then solved (`NightPlanner.solve`):

| class    | exposure       | frames                       |
|----------|----------------|------------------------------|
| HANDHELD | 1/15 -- 1/8 s  | 2 s of burst, 16 -- 28       |
| BRACED   | 1/4 -- 1/2 s   | 4 s of burst, 8 -- 16        |
| TRIPOD   | 1/2 -- 1 s     | the ring's ceiling           |

The exposure is the longest the hand holds at the budget,
`0.75 / (f_px · rate)`, held to the class's range; the ISO is the meter's
light re-split, `ISO_meter · t_meter / t`. The light is kept, not added:
the develop holds its gain within half a stop under and three quarters
over the meter's (`DevelopParams.meteredGain`), and a plan that gathered
more would render a night as a dusk. Where the class's range cannot carry
the light at the sensor's gains, the shutter leaves the range -- shorter
for a bright scene, toward the class's longest for a dark one -- before
the light leaves the meter; a meter frame is at most a frame interval
inside the sensor's range, so the light is in practice always kept
(`NightPlannerTest.lightStaysWithinTheMeteredGainBoundOverTheSensorsRange`).
A HANDHELD hand that cannot hold 1/15 s at the budget still gets 1/15 s;
the plan's `blurPixels` says what that costs.

The plan is shot as a still burst at the press (`ZslRawStream.
captureStillBurst`, "Long exposure belongs to still requests"), its
timestamps validated before the develop queue has it; the repeating request
keeps the meter, at most a frame interval. PRO's manual exposure outranks
the plan, and the Super Res canvas stays a ZSL shot. A press the ring
cannot hold is refused with the reject haptic. The plan is shown as a live
reading in the console, Amber (`HANDHELD · 1/15 · ISO 1600 · 28×`), never
on the photograph. NIGHT has a plan only where `NightPlanner.supported`:
a gyroscope, a manual sensor, and the ISO and exposure ranges. Otherwise
NIGHT is the fixed plan above, unchanged. The classifier's hysteresis,
the table at each boundary and the bound are JVM tests
(`MotionClassTest`, `NightPlannerTest`); the thresholds and the noise
constant have not been measured on either development phone.

**The shutter cap.** AUTO's frames are exposed no longer than
`CaptureModes.AUTO_EXPOSURE_NS` = 1/60 s (ACTION's 1/250 s; NIGHT's 1/4 s
is longer than the frame and so no cap). It was 1/30 s -- the frame
interval, which is to say no cap at all -- and the sixth field test's dusk
pictures were soft in every frame of the burst: the camera's meter
lengthens the shutter before it raises the gain, and a hand does not hold
1/30 s on a 24mm lens. The merge takes the noise out of eight fast frames;
it cannot take the blur out of eight slow ones. Camera2 has no "longest
exposure" for auto-exposure to honour, and the frame-rate range that
bounds the frame cannot tighten on a stream that reads out at 30 fps, so
the cap is a *follower* (`ShutterCap`, pure; `ZslRawStream.setExposureCap`
and `onFramePushed`, on the reader thread as each frame lands):

```
Auto:  the meter has the exposure. Each frame's result is read; when its
       exposure runs past cap · 1.05, the light it chose (ISO · t) is
       re-split as (ISO · t / cap, cap), the frame's mean level from the
       ring's histogram (green sites, linear) becomes the target, and the
       request goes manual.
Held:  each frame exposed at the held values (within 6%; after eight that
       are not, the frame is taken as the hold's at the values the sensor
       reports) is measured; outside a tenth of a stop of the target the
       ISO alone is corrected, at most a stop and a half a frame.
       ISO' would fall under 0.8 · ISO_min → the meter has the exposure
         back (it will keep the shutter inside the cap by itself; if it
         asks for more, it is taken again).
       ISO' would run past ISO_max → the cap gives way: ISO_max, and the
         exposure the light needs, up to the frame. A dark frame is not a
         photograph either; once the gain is spent the blur is the smaller
         loss. (The same decision as the mosaic's `sweepExposure`.)
```

**The frame and its result are paired exactly.** The follower measures a
frame's level on the reader thread, where the slot is certainly that
frame's, and runs only once the result with the same sensor timestamp is
in hand -- at the push if the result came first, from the capture
callback if the image did (`ZslRawStream.onFramePushed`,
`onResultForFollower`). It used to take the newest result within a frame
of the image, and around a change of gain that was the last frame's
settings against this frame's pixels: the hold doubled the ISO, the next
image read a stop short against a result already at the new gain, the
hold doubled again, and a still desk went from ISO 750 to the sensor's
ceiling in three frames.

**The highlight bias, and the guard's memory.** The guard carries a
standing pull of `HIGHLIGHT_BIAS_STOPS` = 1.0 on every scene whose bright
end is above `BIAS_FLOOR_LEVEL` (a tenth of white), before any clipping is
seen, and pulls further -- up to what the burst affords, `0.5 · log2(N)`
stops -- where the histogram shows clipping: −1.0 EV on an ordinary scene
at eight frames, −1.5 on a blown sky, −2.0 on a blown sky at sixteen. The
raw's clip point moves a stop above the scene's bright end; the develop's
own auto-exposure (92nd percentile to 0.40, held within half a stop under
and three quarters over the meter's own gain -- §3.8) puts the midtones
back; the filmic shoulder of §3.1 step 7 spends the stop on the highlights' tones;
the merge takes the shadows' stop of noise back. NIGHT, and AUTO on a dark
scene, run without the guard and without the bias.

The decision is stateful (`ExposureStrategy.Guard`, `nextGuard`; the
stream keeps the last one). The first guard decided from each frame's
histogram alone, and the frame it looked at was the one its own last
decision had darkened: a dim room with a window sat at a bright end of
0.15, the bias went on, the frame read 0.075, the bias came off, with a
period of two decisions for as long as the camera pointed there -- and a
burst taken across the swing held frames at different gains, which is
what the merge turned into the eighth field test's green pictures (§3.2).
So the scene's bright end is read back up by the pull in force before it
is judged against the floor (a clipped frame stays clipped: nothing reads
above one); the bias has hysteresis (off again only under
`BIAS_RELEASE_FRACTION` = 0.7 of the floor); the risk pull beyond the bias
is *raised to* what the frame implies while clipping or a bright end over
the safety line is seen, never accumulated tick on tick (a lamp clips
whatever the pull, and a guard that added to the pull for every clipped
pixel it saw walked a lamp-lit room to the burst's whole allowance), and
relaxes by `RISK_RELAX_STEP` = 0.5 only once the bright end has half a stop
of headroom (`RISK_RELAX_BELOW` = 0.7 of the safety line). From rest on a
still scene it reaches what `recommendedPullStops` says; it differs in
never reversing itself on the strength of a frame it darkened
(`ExposureStrategyTest`, "a dim room with a window does not switch the
bias on and off", "a lamp in the frame does not walk the guard").

The guard speaks to the meter through exposure compensation, which a held
exposure does not hear; while the cap holds, a change in the guard's pull
moves the target by the same stops instead (`ShutterCap.retarget`, told
how many stops *darker*: the stream once passed the change in its pull,
which is negative stops, and every stop the guard took off the hold put
back twice over). A manual exposure is the photographer's and the cap does
nothing; a mosaic's lock takes the exposure first and the cap follows
afresh on unlock. `ShutterCapTest` walks the engage, the hold, the release
with hysteresis, the dark end and the guard.

**The DNG carries the pull undone.** The JPEG shows the scene as metered;
the DNG is the merged raw as exposed, a stop or more under, and a viewer
renders it at the exposure its metadata describes -- which read as "the
RAW files have regressed". DNG has the word for this: `BaselineExposure`
(tag 50730), in stops, which a converter adds to the as-shot exposure
before its own tone curve. The writer (§3.9) writes it with the shot's pull
(`ZslCapture.Shot.pullStops`, read at the press) negated. `DngCreator`
wrote it as zero and gave no way to set it, so until the writer was ours the
value was patched into the eight bytes it left
(`DngMetadata.baselineExposureOffset`); the patch survives only on the
writer's fallback. The pixels are untouched; only the rendering intent is
corrected.

### 3.8 The Rendering, Calibrated on the Desk

**The bench.** `DngDevelopBench` (`app/src/test/.../bench/`) reads the DNGs
the phone wrote -- the merged CFA, the levels and pattern, the colour from
the DNG's matrices and `AsShotNeutral` through the same `ColorScience`
solve as the phone, the lens shading from the opcode list's gain maps,
`BaselineExposure` as the guard's pull undone -- and develops them through
the Kotlin reference pipeline (`RawDeveloper.develop`, which the native
develop is held to within two codes), one JPEG per named variant, in about
a second a frame at half resolution:

```
./gradlew :app:testDebugUnitTest --tests '*DngDevelopBench*' \
    -Pmf.dng.dir=/path/to/dngs -Pmf.out.dir=/path/to/out \
    -Pmf.variants=old,current -Pmf.scale=half        # or full, or -Pmf.crop=x,y,w,h
```

The default fixture directory (when `mf.out.dir` is supplied) is
`app/src/test/resources/dng_samples/september/`: September DNGs pulled from
`/sdcard/DCIM/Multiframe/`. Imported JPEGs and older DNGs are retained but not
selected. Binary fixtures remain local and are not packaged by Gradle; shared
DNG parsing and lens records are in `src/sharedTest/java`.

The bench applies `adaptedTo(dng.iso)` and develops through
`RawDeveloper.developCropped`, the reference's whole §3.1: optics **after
chroma smoothing, defringe and sharpening**, followed by the valid-region crop
(`Optics.applyCropped`), matching production CFA ordering. (Until 20 October
the reference corrected the optics before the chroma smoothing, with the edge
mirrored and nothing cropped, and the bench worked round it by developing
without optics and correcting afterwards; the move changed none of the
bench's 93 September JPEGs by a byte, since the bench's own order was
already production's.) `mf.crop=x,y,w,h` develops
the full-resolution frame first, including full-frame exposure statistics and
shading coordinates, then extracts the inspection window from the corrected
valid image. Coordinates are unrotated. Half-scale CFA decimation is for tone
iteration; use full scale for sharpness. Lens geometry comes from recorded
main/telephoto models in `BenchLenses`; it is not recovered from the DNG and
cannot prove parity with unrecorded optics or cached device CA estimates.

`beforeclip,current` isolates the sensor-clipping recovery. Native comparison
uses `SeptemberDevelopParityTest` with explicit staged fixtures, equal optics
parameters and stated 8-bit tolerance, alongside the synthetic CFA/2× parity
classes. These focused checks replace full-suite runs during tone iteration.

**What it found, 17 September 2026, over fourteen DNGs of the eighth field
test and the day after (a dog on a sofa and in a hallway, a rose in sun, a
backlit tower, a street under cumulus, a tower block against blue):**

| Parameter | Was | Is | Why |
|---|---|---|---|
| `highlightTarget` | 0.62 | **0.40** | The bright end of every scene sat at four fifths of white and the midtones with it: the lifted, veiled rendering. At 0.40 the sky is deep with the clouds' structure kept, the backlit tree is dark as it was, the dog is the colour of the dog. |
| `contrast` | 0.30 | **0.42** | With the bright end placed lower, the S-curve's slope puts the depth back. |
| `LOCAL_TONE_ON` | 0.5 | **0.15** | Half compressed the scene's own light; 0.15 keeps the operator to a little back from the extremes. |
| `Sharpen.amount` / `maxShift` | 0.55 / 28 | **0.45 / 20** | At 100% on brickwork and window frames the old setting was beginning to draw the edge rather than restore it. |
| `meteredGain` (new) | -- | 2^(−pull) | The automatic gain is held within half a stop under and three quarters over the meter's own gain (`RawDeveloper.boundedGain`, the same bound in the native CFA develop and the linear develop of the 2x canvas). Left to itself the placement made every scene the same brightness: a night street lifted until its bright end reached the target, a grey daylight with the noise floor for texture. The develop now puts back what the guard took, plus a nudge for the highlights, and a scene the meter exposed dark stays dark. Zero -- the mosaic's tiles, a DNG written before the tag carried the pull -- leaves the placement alone. |

The bound bites where the pull is known and not where it is not: on the
DNGs written before `BaselineExposure` carried the pull the bench renders
the bounded variants dark, which is the bench's ignorance and not the
phone's -- the phone knows every ZSL shot's pull at the press.
`RawDeveloperTest` holds the bound and the shipped numbers;
`DevelopParityTest`, `LinearDevelopParityTest` and `SubPixelParityTest`
hold the three develops together under them.

**What it found the same afternoon, over the tenth field test's nine DNGs
(the backlit tower on the telephoto, the street under cumulus, the tower
block, the rose, and the earlier day's scenes as controls):** the first
calibration had swung the backlit tower from haze to silhouette -- its
facade seven stops under white, under the toe -- and rendered the rose,
which the eye saw as a soft coral pink, as one slab of scarlet.
`DngAnalysisBench` prints what the develop sees before it renders: the
scene's luminance histogram by stops, its range, and its colour purity by
hue; `DngPetalProbe` follows a saturated red through the chain, camera
space to sRGB. The two showed the causes -- the tower's mode at −7 stops
against the sky's at −2, and the matrix taking the petals' green from three
tenths of red to one tenth -- and the fixes are structural, not a turn of a
dial:

| Parameter | Was | Is | Why |
|---|---|---|---|
| `shadowLift` / `shadowLiftMax` (new, §3.5) | -- | **1.0 / 3.0 stops** | The local operator's shadow side: a neighbourhood under the scene's bright end raised by up to a ceiling the scene's range earns -- a stop per stop beyond 3.3, so the dog and the rose earn nothing and the backlit scenes earn 2.4 to 3.0. Anchored on the bright end, so the sky does not move. The tower's arches and corbels are readable (`lift10m30`); the dog on the sofa is byte-identical to before. |
| `BilateralGrid` cell | 16 px | **shorter side / 24** | At 16 px the base resolved the tower's own architecture and lifting the base flattened it; at 127 px the base is the illumination and the architecture is detail. The bench's half-size develop reads half the cell and so judges the same neighbourhood. |
| `purityThreshold` / `purityCeiling` (new, §3.1 step 6a) | -- | **0.70 / 0.85** | The matrix's overshoot on a spectral colour compressed before the tone chain, hue kept exactly. The rose goes from sRGB (239, 67, 65) to a coral (230, 115, 110) with its petals' shading back; the sky, at six to seven tenths of purity, is untouched (`pu70c85` against `nopurity`). |

Tried and not taken: the shadow lift anchored on the scene's mean (the
tower needed to overshoot the mean to read; a slope past one would invert
neighbourhoods) or on its median (the median flips four stops as a
composition changes); a two-stop dead zone with a steeper ramp (the street's
foreground flattened to soup at a slope of 0.94); a chroma path through the
shoulder that spent a saturated colour's compression on its chroma rather
than its level (real, but an afterthought once the purity was compressed,
and removed); thirty-two bins (the rings round the sun the bench showed were
in the raw -- the lens's flare -- not the grid's quantisation, and the
phone's JPEG has them too).

The DNG now says what the JPEG did in one more way: `AsShotNeutral` is
the develop's camera neutral (written by §3.9's writer; patched beside
`BaselineExposure` on its fallback, `DngMetadata.asShotNeutralOffset`), since
on the 11 Pro's telephoto the HAL's neutral colour point and its gains
disagreed by a fifth of a stop in blue and the bench -- and every converter
-- rendered the telephoto's clouds warm where the phone's were white. DNGs
written before that carry the neutral point, and the bench renders them
with it.

### 3.9 The DNG, Written by Hand

The DNG is ours: `DngWriter` lays a `DngRecord` into one little-endian TIFF
directory (`TiffDirectory`), and `DngCamera2` reads the record's values from
the camera. No framework class and no library sits between the merge and the
file. `DngCreator` wrote DNG 1.4 that the app then patched in place
(`BaselineExposure`, `AsShotNeutral`), and it cannot write a gain table map at
all.

**The file.** The header, IFD0 at offset 8 with its entries sorted, every value
longer than four bytes after it on a word boundary, then the raw. The raw is the
merged CFA as the merge left it: 16-bit little-endian, a row to a strip,
starting on a sixteen-byte boundary. It is handed to the descriptor's
`FileChannel` as the merge's direct buffer, so it reaches the file with no Java
copy (`ParcelFileDescriptor.AutoCloseOutputStream(...).channel`). Opcode lists
and the gain table maps are big-endian, as the specification gives opcode lists.

**What it says, and where each value comes from.** These are the tags `DngCreator`
wrote on every September file, read from the keys it read:

| Tags | Value | Source |
|---|---|---|
| 254, 256-259, 262, 277, 284 | main image, W x H, 16 bit, uncompressed, CFA, 1 sample, chunky | the raw |
| 270, 33432 | empty ImageDescription, Copyright | -- |
| 271, 272, 50708, 305 | Make, Model, `MODEL-MANUFACTURER-BRAND`, the build fingerprint | `Build` |
| 274 | Orientation | `CaptureOrientation.exif` of the shot's rotation |
| 282, 283, 296 | 72/1 dpi, inches | -- |
| 306, 36867 | DateTime, DateTimeOriginal | now, less the frame's age on the realtime clock |
| 33421, 33422, 50710, 50711 | 2x2 pattern, CFAPattern, planes R G B, rectangular | `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT` |
| 33434, 33437, 34855, 37386 | exposure (ns/1e9), f-number, ISO, focal length (hundredths) | the result |
| 37398, 50706, 50707 | TIFF/EP 1.0.0.0; DNG 1.7.1.0; backward 1.1.0.0 | -- |
| 50713, 50714, 50717 | black per site in hundredths; white | `SENSOR_DYNAMIC_BLACK_LEVEL`/`_WHITE_LEVEL`, else the static ones |
| 50718-50720, 50829 | scale 1; crop 8 px each side; ActiveArea the whole raw | -- |
| 50721-50724, 50964, 50965, 50778, 50779 | colour, calibration and forward matrices; their illuminants | the characteristics, rationals as given |
| 50728 | AsShotNeutral | the develop's camera neutral in millionths (§3.8); the HAL's neutral where none is given |
| 50730 | BaselineExposure | the guard's pull undone, hundredths of a stop (§3.7) |
| 51009 | OpcodeList2: a GainMap per site, red, even green, odd green, blue | `STATISTICS_LENS_SHADING_CORRECTION_MAP` |
| 51022 | OpcodeList3: WarpRectilinear | `LENS_DISTORTION`, `LENS_INTRINSIC_CALIBRATION` |
| 51041 | NoiseProfile, a pair per plane from its first site | `SENSOR_NOISE_PROFILE` |
| 52525, 52544 | ProfileGainTableMap, ProfileGainTableMap2 | the local operator, below |

The warp: Camera2's model is in units of the focal length, DNG's in units of the
centre-to-farthest-corner distance `d`, so with `m = d / fx`, `kr_i = k_i m^2i`
and `kt = p m`. The whole warp is then scaled by the largest zoom `kr0 = 1 -
0.002 n` that keeps the source of every corner and edge midpoint on the array
(a hundred-thousandth of `d` of slack), so the correction reads no pixel the
sensor did not. `DngCreator` zooms the same way; on 86 of the 93 September files
the zoom is its, on the other 7 one step (0.2 %) larger.

**Held tag by tag.** `DngWriterParityTest` rebuilds each September file's record
from its own tags, writes it, and compares: on all 93 files every one of the 52
compared tags has the same type, count and value bytes, and the raw is identical
byte for byte. The lens shading opcodes, rebuilt from the decoded shading, are
DngCreator's byte for byte. `DngWriterDeviceTest` is the other half -- our
record of a live capture against `DngCreator`'s, tag by tag -- and needs the
phone.

**Intended differences from a `DngCreator` file.**

- DNGVersion 1.7.1.0, not 1.4.0.0: ProfileGainTableMap2, the tag with the gamma,
  is a 1.7 tag. DNGBackwardVersion stays 1.1.0.0, as the specification asks of
  optional tags (compatibility issues 15 and 16).
- StripOffsets: the raw starts on a sixteen-byte boundary after a directory laid
  out in our own order. The strips themselves are identical.
- BaselineExposure and AsShotNeutral carry the develop's values from the start
  (on September files they were patched, or carried the HAL's neutral in 1/1024).
- The WarpRectilinear's zoom, as above.
- The two gain table maps, when the shot was developed with LOCAL TONE.
- Orientation is the shot's: the September files carry 9, `DngCreator`'s value
  when it is not told; production has set it since.
- No thumbnail or preview: `DngCreator` wrote none for us either (it writes one
  only when given a bitmap, and the app never gave it one).

**The fallback.** `DngCamera2.record` returns null, and `DngCreator` writes the
file with the two tags patched in as before, in two cases the writer cannot
represent: a sensor that is not a 2x2 Bayer mosaic, and a camera that publishes
no `SENSOR_COLOR_TRANSFORM1` through its characteristics -- where `DngCreator`
reaches the calibration by a route an application does not have (`DngMetadata`).
Neither development phone is either.

#### 3.9.1 The Local Operator as a Gain Table Map

The operator of §3.5 scales a pixel by `2 ^ lift(base)`, where the base is the
grid sliced at the pixel's position and its own luminance: a function of where
the pixel is and how bright it is, and of nothing else. That is the shape of a
DNG gain table map -- a grid of one-dimensional gain tables, bilinearly
interpolated in space, indexed by the pixel's own colour, the gain multiplying R,
G and B equally -- so one table per grid node, sampled along the luminance axis,
is the operator. It is computed from the same splats and parameters as the
JPEG's (`ProfileGainTableMap.forCapture`, on every other 2x2 cell of the merge,
which reads the same neighbourhoods, §3.5), deterministically and with no model.

- *Space.* Node `i` sits at pixel `(i + 0.5) cell`, so in the map's half-pixel
  relative coordinates the origin is half a cell and the spacing one cell
  (`cell / W`, `cell / H`); past the outer nodes both grid and map hold the edge.
- *Index.* `MapInputWeights` are the Y row of ROMM RGB to XYZ (0.2880, 0.7119,
  0.0001, 0, 0): the index is luminance, the quantity the operator splats, in
  RIMM, whose white is the develop's white. A converter's linear values are the
  develop's divided by `exposureGain / 2^BaselineExposure`, so the table at
  index `t` holds the operator's gain at luminance `u(t) exposureGain /
  2^BaselineExposure`.
- *ProfileGainTableMap2* (52544, DNG 1.7): 128 float gains per node, `Gamma` =
  1/4, so `u(t) = (t / 128)^4` and the shadows -- where the lift is -- have the
  table's resolution. *ProfileGainTableMap* (52525, DNG 1.6, no gamma): the same
  nodes, `u(t) = t / 128`, for a reader that knows only 1.6. A 1.7 reader
  applies only the 1.7 tag.
- *Index 0* is black, where the operator returns 1 for want of a luminance; it
  holds the gain just above the operator's floor, so the first interval does not
  ramp from 1.

Size: a 4080x3064 frame has 32 x 24 nodes, so each tag is about 393 KB beside
the 25 MB raw. Only a shot developed with LOCAL TONE carries them.

**The desk proof** (`ProfileGainTableMapTest`): a reader of the tag written from
the specification's text alone, fed the pixel's linear RIMM colour at
BaselineExposure, against the operator on the same pixel, every third pixel of
the five representative September fixtures at half scale, pixels with RIMM
luminance under one. Two comparisons, in stops of |log2(tag / operator)|:

| | 1.7 encoding median / p99 | 1.7 total median / p99 | 1.6 total p99 |
|---|---|---|---|
| Backlit tower (ceiling 2.95) | 0.0004 / 0.0087 | 0.0005 / 0.072 | 0.71 |
| Flower | 0.0003 / 0.0033 | 0.0010 / 0.040 | 0.048 |
| Dog | 0.0001 / 0.0031 | 0.0001 / 0.0033 | 0.0036 |
| Street (ceiling 3.00) | 0.0008 / 0.0206 | 0.0011 / 0.118 | 0.14 |
| ISO 918 | 0.0002 / 0.0028 | 0.0003 / 0.0032 | 0.0036 |

*Encoding* asks the operator at the tag's own luminance: what the tag can
represent, held under 0.002 / 0.03 stop. *Total* asks it as the develop does --
after purity compression, on sRGB luminance -- which is what a converter will
differ by, held under 0.005 / 0.15 stop; the difference is the saturated sky and
foliage, whose luminance purity compression moves and a converter does not. The
1.6 tag is reported, not held: without a gamma its first sample is seven stops
under white, and the tower's facade is under that.

**An open question for a converter.** The specification gives the gain table
maps no byte order of their own. Ours are big-endian, like the GainMap opcode
whose fields they share; a reader that takes the file's order would read them
wrongly. `work/dng-writer-20261021/` has the tower with the payload each way,
for Lightroom to answer.

### 3.10 The Long Exposure: the Stream Averaged

NIGHT offers a long exposure of 2, 4 or 8 seconds (`LongExposureCapture`,
the console's `Long` control, NIGHT only, only where the ZSL stream runs).
After the press, every frame the ring takes for that span is merged into one
raw picture; the result goes through the ordinary develop and DNG writer.

**Why an average.** A single multi-second frame clips every highlight in
daylight, and a phone has no neutral-density filter. N frames of exposure
t_f each, averaged, render motion over the span T = N·t_f(1+gap) while every
frame keeps the highlight guard's exposure; the sum is held in floats, so the
average clips nothing a single frame did not. Noise falls as 1/√N, as in the
burst merge.

**Rejection off.** The merge's finite-support weight (§3.2) drops a sample
whose residual exceeds 2n. In a long exposure the moving part *is* the
picture, so the accumulator is created with `noiseTolerance = 1e6`
(`LongExposureCapture.NO_REJECTION_TOLERANCE`): every residual is inside n and
every sample keeps weight 1, which makes the merge the plain mean. The
gain normalisation `black + (raw − black)/g` is unchanged, so a meter that
moves during the span does not step the picture's brightness.

**Alignment.** On a tripod (`MotionClass.TRIPOD`) frames are summed where they
land; aligning would chase the water. Otherwise each frame is aligned onto the
first by the burst's tile search (§3.3) and the CFA offsets stay even. Frames
are taken newest-first as fast as alignment allows; the log records how many
of the span's frames were sampled. A sampled rate below the stream rate leaves
gaps in fast light trails -- the cost to measure on the phone, and the reason
a pure-sum NEON kernel is the next step if it shows.

**Reference.** The first frame after the press, not the newest: the long
exposure is the span after the shutter, and the first frame is the one the
photographer framed. With rejection off the reference sets geometry only.
This is the one exception to "the reference frame is the newest".

**No haptics during the span**; `Haptics.mechanicalRelease` fires after the
last frame.

### 3.11 Content Credentials: the Promise, Signed

Every JPEG carries a C2PA manifest (`ContentCredentials`, the `CONTENT
CREDENTIALS` setting, on by default; HEIF is not signed in this version and
the row is absent while HEIF is chosen). It is built and signed on the phone,
offline; nothing is fetched or sent.

**Assertions.** `c2pa.actions.v2` with one `c2pa.created` action whose IPTC
digital source type is `computationalCapture` -- a real scene, captured by a
camera, processed by algorithms such as multi-frame merging. Not
`digitalCapture` (a single unprocessed exposure) and never
`trainedAlgorithmicMedia`. `dev.multiframe.capture` carries the photograph's
report (§3.10's sibling, `CaptureReport`) and `generative_ai: false`.
`c2pa.hash.data` binds the manifest to the pixels.

**The data hash.** The manifest is a run of APP11 (JPEG-XT, CI "JP") segments
inserted at offset P, after SOI and any APP0/APP1 and before an Ultra HDR
file's APP2 MPF segment (its offsets are relative to the MPF header, so an
insertion ahead of it leaves them valid). The hash assertion excludes exactly
[P, P+L). The bytes it covers are then the file before insertion, so the hash
is SHA-256 of that file, computed once. L depends on the manifest's size,
which depends on L only through the CBOR width of the exclusion; the builder
rebuilds with a placeholder signature of the final size until L is fixed,
then signs once.

**Signature.** COSE_Sign1 (tag 18), ES256, detached payload: the claim's CBOR
bytes, with `x5chain` in the protected header. Java's DER signature is
converted to COSE's 64-byte r‖s. Hashed URIs cover each assertion superbox
without its 8-byte header.

**Keys.** Two Android Keystore keys, EC P-256, StrongBox where present
(`KeystoreCredentialSigner`): a device root (CA, keyCertSign) and a
claim-signing leaf (digitalSignature; EKU emailProtection and
documentSigning, which the C2PA certificate profile accepts). The
certificates are written by `Der` and signed by the Keystore; the Keystore's
own certificate carries no EKU and a validator rejects it.

**What a verifier shows.** On the desk (c2patool 0.27.22,
`work/c2pa-20261022/`): the signed sample is `Valid` -- claim signature
validated, every hashed URI matched, data hash matched -- with one failure,
`signingCredential.untrusted`, because the device root is on no trust list.
A tampered copy reads `Invalid` with `assertion.dataHash.mismatch`. A
trust-listed signer needs a certificate from a C2PA-recognised authority;
that is a business decision outside the app.

**Order.** Signing is the last write before the file is published (after
EXIF and the gain map): any later write breaks the hash.

### 3.12 The Focus Stack (Labs)

Labs offers a focus stack where the camera focuses by hand
(`CameraCapabilities.hasManualFocus`) and the ring holds eight frames
(`FocusStackCapture.supported`). Armed from Labs, the next press takes one
still burst with a manual focus distance per frame, eight steps uniform in
diopters from the nearest focus to infinity (depth of field is roughly
constant in diopters), and `FocusStack` merges it.

**Measure.** Per 2×2 CFA cell, on the half-resolution luma proxy after
alignment: the absolute Laplacian |4c − n − s − e − w|, box-averaged over a
13×13 window (R = 6) via an integral image, so a flat patch beside an edge
takes the edge's verdict.

**Blend.** Every frame contributes to every cell with weight (S + ε)⁴: close
to choosing the sharpest frame where one clearly wins, a soft blend where two
are equally sharp, which keeps the seams between focus zones from showing as
halos. One weight per cell, applied to its four samples, at the burst's even
CFA offsets, so colour phase is kept. Frames are gain-normalised as
`black + (raw − black)/g`.

**Alignment.** Each frame is aligned to the previous one in the sweep and the
offsets are chained: frames a focus step apart are sharp in mostly the same
places, while a block match between a blurred patch and a sharp one is
guesswork. The chained field is median-filtered per tile over its 3×3
neighbourhood -- breathing makes a smooth field, so an isolated disagreeing
tile is a mismatch. On the desk (`FocusStackTest`), two frames each sharp in
one half stack to within 0.7 codes RMS of the sharp scene in both halves,
against ~187 for the defocused input.

**Not the merge.** The merge rejects what differs between frames; here the
frames differ on purpose. The stack is written through the ordinary develop
and DNG writer, tagged `stack`, with its report (§3.11) saying so.

### 3.13 Astro: Minutes of Night Sky

NIGHT offers `Astro: 1 / 2 / 4 / 8 / 15 min` where the camera takes manual
exposure and the ring holds a chunk of frames (`AstroCapture.supported`). The
press is refused unless the gyroscope reads tripod (`MotionClass.TRIPOD`).
Pixel's own astrophotography stops at four minutes; this has no ring-imposed
limit, because frames are merged as they arrive and released.

**Plan** (`AstroPlanner.solve`). Frame time t = min(sensor's longest,
1.5 px / (f_px · ω), 16 s), ω = 7.2921·10⁻⁵ rad/s (sidereal): a star drifts
at most 1.5 px within one frame -- 7.2 s on the 11 Pro's main lens (f = 2853
px), 0.9 s on the 110 mm. Gain = half the meter's light over t (the guard's
standing stop), held between the sensor's floor and its analog ceiling --
past it the gain is digital and adds nothing the merge cannot. Frames = span
/ t. The develop's metered gain gives the stop back.

**Capture.** Still bursts of six (`captureStillBurst`), each frame merged
then released; memory is two accumulators and the frames in flight, never
the span. Cancel, or a thermal status of SEVERE (`AstroCapture.tooHot`), ends
the span early and keeps the stack: the frames so far are merged and written.

**Hot pixels** (`HotPixelMap`). From the first four frames: a pixel more than
48 codes over the mean of its four same-colour neighbours in at least 75% of
them is the sensor, not a star (a star moves). Marked pixels are replaced by
that mean in every frame before it is merged, so an aligned sky does not draw
their dotted trails.

**Two stacks and a mask.** Every frame is merged into a *sky* stack, aligned
by the tile search, and a *ground* stack, unshifted; both keep the motion
rejection. Tiles that did not move (mostly ground) are given the frame's sky
motion in the sky stack -- the median offset of the tiles that moved -- so a
sliver of sky over the horizon is still aligned. Each frame's search starts
from the previous frame's sky motion (the proxy is shifted by it first), so a
fifteen-minute drift (~125 px at 24 mm) stays inside the search's reach.

**The measured mask** (`SkyMask`, the default). Per proxy pixel, summed over
the stack: |unshifted − reference| − |sky-aligned − reference|. Positive means
the pixel moves with the stars. Box-smoothed over 13×13 proxy pixels and
mapped through a logistic (½ at zero, softness 0.9 codes), it blends the sky
and ground stacks per CFA cell. On the desk (`AstroTest`) a drifting star field
over static ground reads 0.99 sky and 0.00 ground. No model is involved; the
mask is the sensor's own evidence.

**The model mask** (Labs, opt-in, `SkySegmenter`). Where the user enables it,
a segmentation model's sky probability replaces the measured mask. It only
chooses which of two stacks of measured samples a pixel takes; it computes no
pixel value. The photograph's report and Content Credentials name the model
(`ml_analysis`) and its role. The charter permits exactly this use and
requires the measured default beside it.

**The model's provenance.** Multiframe's own: trained only on pairs this app
collects (Labs, "COLLECT TRAINING PAIRS", off by default, kept on the phone)
-- an astro capture's reference frame preprocessed as the phone feeds the
model (`SkyModelContract`: 256×192, stretched to its 99th percentile) and the
*measured* mask as the label. No third-party dataset. `tools/analysis_models/` (`--task sky`)
trains a small U-Net and exports it through a SavedModel to TFLite; the
contract is checked at export (input and output [1, 192, 256, 1] float32).
A smoke run on synthetic scenes exported a 799 KB model; no model ships until
it is trained on real captures. Runtime: LiteRT 1.4.2 on the CPU, the model
memory-mapped uncompressed from the APK; the Labs row is absent until
`assets/sky_segmenter.tflite` exists.

**Limits to measure on the phone.** The sensor's longest exposure (the probe
records only the shortest), heat over a long span, battery, and how visible
the horizon boundary is on real foregrounds.

### 3.14 HIGH RES: the Full Frame on the 2x Canvas

A setting, off by default, offered wherever the 2x canvas is
(`CameraCapabilities.supportsSuperRes`). With it on, a PHOTO press at 1x
takes the 16-frame burst onto the sub-pixel canvas (§3.6) for the whole frame:
about 48 MP from a 12 MP readout (`SuperResPolicy.wantsCanvas`, `highRes`).
NIGHT is unchanged; its frames are spent on noise.

**Why not the sensor's own 50 MP.** Pixel's 50 MP mode reads the quad-Bayer
sensor remosaiced at full resolution. Neither development phone exposes that
readout to applications (`ULTRA_HIGH_RESOLUTION_SENSOR` absent, no
maximum-resolution stream map; §2.4), so `supportsFullResolution` is false and
the `12 MP | 50 MP` chip is absent. Where a phone does expose it, both are
offered: the chip reads the sensor, HIGH RES merges frames.

**What the extra pixels are.** Measured samples: the hand's sub-pixel motion
places each frame's samples between the reference's, and the canvas keeps
them where they fell (registration median ~0.05 px since the Lucas-Kanade
refinement, §3.3.1). Where a burst lacks that motion -- a tripod, a flat
scene -- the coverage gate declines the canvas and the photograph is saved at
12 MP, with the reason in its status and report. On the desk the canvas
resolves finer star detail than a Lanczos enlargement on 16-frame bursts
(0.47–0.55 vs 0.35–0.39 cy/px) but not yet higher MTF50 (0.329 vs 0.335): its
reconstruction filter is the remaining limit, and real-photo detail is
unproven until the phone shoots a chart.

### 3.15 Analysis Models (Labs)

The charter permits a model only to *analyse* measured data, opt-in, with a
deterministic default doing the same job, and disclosed in every photograph
it touches. Six tasks share one registry (`AnalysisModels.kt`): one LiteRT
runner, one training-pair format, one disclosure (`AnalysisUse`, written to
the report's `ml` key and to the Content Credentials' `ml_analysis` list with
each model's name, the first eight hex digits of its file's SHA-256, and what
its output chose). A task's Labs row is absent until its `.tflite` is in
`assets/`; the deterministic choices are offered regardless. No model is
bundled yet.

| Task | What the model returns | Deterministic default | Label measured on the phone |
|---|---|---|---|
| sky (§3.13) | sky probability per proxy pixel | the stack's measured `SkyMask` | the measured mask |
| awb | the illuminant's (u, v): three WB multipliers | the camera's AWB | the WB set by hand in PRO |
| subject | a 64×48 heatmap; its confident peak places AF/AE | whole-frame AF/AE; a tap always wins | the point tapped to focus |
| frame | each candidate frame's blur in proxy px | NEWEST, or SHARPEST (Tenengrad) | gyro rate × exposure × px/rad |
| regions | sky, skin, foliage masks | the colour classifier | none (a dataset chosen by hand) |
| person | a person mask | the merge's motion rejection; no blur | none (a dataset chosen by hand) |

**Learned white balance** (`AwbContract`). Input: a 64×64 log-chroma
histogram, (u, v) = (ln g/r, ln g/b), of the merged CFA's 128×96 camera-RGB
cells (`CfaThumbnail`), two channels: the cells weighted by √brightness, and
their neighbour differences (the grey-edge cue). A change of illuminant
translates the histogram (`AnalysisModelsTest`), which is why it, not the
picture, is the input. Dark and near-clipped cells are left out. Output: the
illuminant's (u, v); neutral (e^-u, 1, e^-v), held inside the histogram's
span. It replaces the camera's neutral in `ColorProfile.calibrated`, which
re-solves the matrix for it, and the DNG's `AsShotNeutral` carries the same.

**Subject focus** (`SubjectFocus`, `ZslRawStream.subjectFocus`). About once
a second, off the main thread, the newest ring frame's luma (256×192, the
whole array) goes to the model; a peak over 0.5 sets the AF and AE regions
there with no trigger (continuous AF follows). A tap's region is never
replaced; a subject outside the zoomed field is ignored; manual focus or
exposure stops it. A photograph taken while the model's region was in force
names it.

**Reference frame** (`ReferenceChooser`). Of the newest three frames, the
sharpest becomes the reference only if clearly so: 15% more gradient energy
(Tenengrad over the centre, over mean² so gain does not count) than the
newest, or under the model 0.5 proxy px less blur. Never under DCG (the
lowest-ISO frame keeps the highlights) or for the long exposure and astro
(the first frame by design).

**Region-aware tone** (`RegionTone`). Memberships per 256×192 cell, from the
colour classifier on the merged CFA's balanced colours (sky: hue 190–255°,
saturated, bright, nearer the top; foliage: hue 65–165°, saturated; skin:
hue 5–45°, saturation 0.12–0.75) or from the model; averaged into the develop
grid's cells; scale = max(skin, 1 − 0.6·sky − 0.25·foliage) in [0.4, 1].
Computed before the DNG writer starts, so the DNG's gain table (§3.9) is the
same operator. Only where LOCAL TONE is on.

**Person-aware merge** (`PersonMask`). After the merge, the weight per cell
is the person probability times the motion evidence -- the reference's and
the merge's cells differing by 4–12% relative (smoothstep) -- and the merged
CFA is blended toward the reference frame's own samples by it. A person who
moved is one frame's measured samples, with one frame's noise; a person who
stood still is merged. The DNG carries the blended CFA. Not on the 2x canvas.

**Simulated background blur.** §3.1 step 11a.

**Training.** `tools/analysis_models/train.py --task <task>` reads the MFP1
pairs the phone collects (Labs → Analysis → COLLECT TRAINING PAIRS; sky's
from Astro's own switch), or for regions and person an image/mask dataset,
and exports through a SavedModel with the contract checked. All six tasks
smoke-train and export (sky 799 KB, awb 113 KB, subject 204 KB, frame 112 KB,
regions and person 800 KB); none is a real model and none ships.

## Appendix: File Inventory by Subsystem

### Pipeline (app/src/main/java/dev/multiframe/camera/pipeline/)

| Subsystem | Files |
|---|---|
| **Capture & ZSL** | `ZslRawStream.kt`, `ZslCapture.kt`, `ZslPolicy.kt`, `RawRing.kt`, `BurstBuffer.kt`, `RawCapture.kt`, `RawBurstCapture.kt` |
| **Merge** | `BayerMerger.kt`, `BayerFrame.kt`, `Merger.kt`, `NativeMerge.kt`, `Aligner.kt` |
| **2x canvas** | `SubPixelAccumulator.kt` (reference), `NativeSubPixel.kt` (strips, and the develop entry points), `SubPixelCoverage.kt` (the prediction and its bounds), `LinearDeveloper.kt` (the linear develop, reference), `SuperResCapture.kt` (the outputs) |
| **Analysis (Labs)** | `AnalysisModels.kt` (registry, LiteRT runner, training pairs), `AnalysisChoices.kt` (the choices, `CfaThumbnail`), `LearnedWhiteBalance.kt`, `RegionTone.kt`, `ReferenceChooser.kt`, `PersonMask.kt` (person merge, `PortraitBlur`), `SubjectFocus.kt`, `SkySegmenters.kt` (§3.15) |
| **Develop** | `RawDeveloper.kt`, `Demosaic.kt`, `ToneCurve.kt`, `BilateralGrid.kt` (local tone, reference), `HdrGainMap.kt` (the HDR rendition's gain map, §3.1 step 6b), `UltraHdr.kt` (its platform encode), `ColorScience.kt`, `ColorProfile.kt`, `LensShading.kt`, `Sharpen.kt`, `Defringe.kt`, `HotPixels.kt`, `ToneAblation.kt` |
| **Output** | `ImageSaver.kt`, `NativeJpeg.kt`, `ExifWriter.kt`, `DngWriter.kt` (the DNG, §3.9), `TiffDirectory.kt` (its IFD), `DngCamera2.kt` (its values from the camera), `DngOpcodes.kt` (shading and warp opcodes), `ProfileGainTableMap.kt` (the local operator as a gain table map), `DngMetadata.kt` (reads a DNG's colour tags; places `BaselineExposure` and `AsShotNeutral` for the `DngCreator` fallback, §3.7, §3.8) |
| **Exposure** | `ExposureStrategy.kt`, `CaptureMode.kt`, `ShutterCap.kt` (shutter priority under the meter, §3.7) |
| **Camera** | `CameraCapabilities.kt`, `LensCatalog.kt`, `LensProfile.kt`, `ManualSettings.kt`, `TouchFocus.kt`, `FocusPeaking.kt`, `OrientationTracker.kt` |
| **DCG calibration** | `DcgCalibration.kt`, `DcgProfiler.kt`, `DcgCalibrationStore.kt` |
| **Super Res (mosaic)** | `SuperResMachine.kt` (the mode's lifecycle), `MosaicSession.kt`, `MosaicCapture.kt` (the guided runner), `ImuGuidance.kt` (the dots, from the attitude), `MotionSensor.kt` (gyroscope and rotation vector), `StabilityGate.kt` (the ceiling, the shutter floor), `MosaicGuide.kt` (the lock rule, the magnet), `MosaicPlanner.kt` (presets, regions, nodes), `MosaicAssembler.kt` (anchor, locate, place), `MosaicCanvas.kt`, `MosaicPlacement.kt` (the correction fitted from the canvas's block matches, §3.4.4), `MosaicRefiner.kt` |
| **Memory** | `MemoryBudget.kt`, `MemoryPressure.kt` |
| **Settings** | `AppSettings.kt`, `CaptureReadout.kt`, `RecentCapture.kt`, `CaptureLog.kt` |
| **GPU** | `GpuCrossing.kt` (benchmark only) |
| **Geometry** | `Homography.kt`, `FeatureMatcher.kt` |
| **Sensor** | `LevelGauge.kt`, `YuvFrame.kt`, `TripodDetector.kt`, `DitherController.kt`, `DitherPulse.kt` |

### Native C++ (app/src/main/cpp/)

| File | Purpose |
|---|---|
| `bayer_merge.cpp` | NEON-vectorized merge accumulation, noise estimation, shading correction, prepass, the CFA develop. |
| `Tone.h` | The tone chain shared by both develops: gamma and display tables, the shoulder table, the purity compression (§3.1 step 6a), `renderLinear`, shading interpolation, the band scheduler, and the `BilateralGrid` local tone operator with its shadow lift (§3.5). Header-only. |
| `SubPixel.cpp` | Sub-pixel kernel accumulation onto a 2x canvas, strip-major over an mmap output, tiled for cache residency (§3.6). Held to `SubPixelAccumulator.kt` within one 16-bit count. Also the JNI for the canvas's develop. |
| `LinearDevelop.cpp` / `.h` | The develop for the 2x canvas: black, shading, white balance, matrix and tone from `Tone.h`, sharpen on band-local luma, into three-byte RGB with the canvas released strip by strip, then the strip encoder (§3.6.5). Held to `LinearDeveloper.kt`. |
| `Align.cpp` | Native 4-level pyramid tile alignment with equiangular sub-pixel refinement at the finest level (§3.3.1). Held to `Aligner.kt` at zero differing tiles. |
| `RingBuffer.cpp` / `.h` | mmap-backed raw frame ring: push, per-slot lock and release, fresh-frame counts by timestamp, in-place histograms (§3.7) |
| `Mosaic.cpp` / `.h` | Native mosaic canvas: running-mean compositing in three frequency bands with the detail band cut by seam (§3.4.3), the refinement of a tile's placement against the canvas (`RefineTile`, §3.4.4), flattening and compression from its own pages |
| `Rotate.cpp` | NEON-accelerated image rotation |
| `Jpeg.cpp` / `.h` | libjpeg-turbo strip encoder, parallel; the header exposes it to the linear develop for RGB from native memory |
| `GpuCrossing.cpp` | Vulkan crossing cost benchmark (not production) |

### UI (app/src/main/java/dev/multiframe/camera/ui/)

| File | Purpose |
|---|---|
| `TitaniumTokens.kt` | The one palette, type scale and geometry of the Titanium Rangefinder interface (§1.1–1.2) |
| `Theme.kt` | `Ink`/`Type` aliases of `TitaniumTokens`, the `Haptics` vocabulary (§1.3), `MultiframeTheme` |
| `Revision05Geometry.kt` | The fixed root allocation: image, controls and HUD regions from window geometry |
| `Revision05Console.kt` | The console: Pro tabs, replacement panels, footer with modes and shutter; `InstrumentButton`, `Revision05TopDeck`, `Revision05ManualPanel` |
| `MechanicalConsole.kt` | `MechanicalTurret` (the reachable focal stops) and `MechanicalShutter` |
| `RangefinderDial.kt` | The manual dial: ISO, shutter, focus, frames, with detents and coasting |
| `RangefinderShutter.kt` | `MechanicalClick`, the shutter sound |
| `RangefinderDiagnostics.kt` | Overlay policy and semantics hooks the device tests read |
| `TitaniumReticle.kt` | Focus target, tap-to-focus brackets and the loupe frame, all telemetry white |
| `TitaniumTelemetryDeck.kt` | `TitaniumResolutionSelector`, the `12 MP | 50 MP` choice where the capability exists |
| `WhiteBalancePanel.kt`, `CompactWhiteBalancePanel.kt` | White balance: Auto, Lock and calibrated Kelvin/tint, each only where supported |
| `FocusAssist.kt`, `PeakingPreview.kt` | The loupe inset and the cyan peaking overlay while manual focus is engaged |
| `PreviewRgbHistogram.kt` | The opt-in preview RGB histogram |
| `CaptureFeedback.kt`, `MergeProcessingIndicator.kt` | Haptics after the frames are locked; the develop queue's progress |
| `CameraViewport.kt`, `WindowLayout.kt` | The viewport fit and the layout decision for the window and the hand (§1.4) |
| `InstrumentSurface.kt` | The milled finish drawn under the console |
| `ControlBar.kt` | Control data model: `ControlSpec`, `ControlBarState`, `Detents`, and the settings screen's chips |
| `SettingsScreen.kt` | Everything set once: the settings rows, `ServiceCaptureSettings`, Labs |
| `LightboxScreen.kt`, `PhotoShare.kt` | Review with pixel-level zoom, delete, and the Sharesheet hand-off |
| `BackNavigation.kt` | What the system back gesture does, given what is open: a pure stack, tested |
| `Onboarding.kt` | The first screen, once: the statement and the camera permission (PRD §2.2.7) |
| `Guides.kt` | Composition guides and the Labs mosaic guidance |
| `Gauges.kt` | `GaugeState` and the shutter label |
| `Revision05SweepMarker.kt` | The Labs Brenizer sweep marker |
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
| androidx.window | 1.5.0 (folding features, §1.4) |

**The release build.** `assembleRelease` shrinks with R8 (`isMinifyEnabled`,
`isShrinkResources`, `proguard-android-optimize.txt` plus
`app/proguard-rules.pro`) and signs from `keystore.properties` where that
file exists; without it the APK is unsigned and `apksigner` with any key
makes it installable for a device run. The rules keep, by member, the nine
classes that carry `external fun`s -- `RawRing`, `NativeMerge`, `Aligner`,
`NativeSubPixel`, `MosaicCanvas`, `NativeOptics`, `NativeRotate`,
`NativeJpeg`, `GpuCrossing` -- so JNI's link by fully qualified name holds;
the native side makes no upcalls, so nothing else is named. The shrinker is
allowed to *remove* a native method it can prove unreachable (a bench, a
test-only accessor, the defringe that no setting turns on), which cannot
break the link because the Kotlin call is gone with it; what it must not do
is *rename* one, and `tools/verify-native-bridge.sh` reads the release DEX's
native methods and the `.so`'s exported `Java_` symbols and fails on any
method without its symbol. On 16 September 2026 the release build carried 45
native methods in 8 classes against 58 exported symbols, every one matched,
13 removed as unreachable; installed on the Pixel 9 Pro XL it opened the
ring, took a burst, merged, developed, corrected and wrote JPEG and DNG
through every native stage. The release APK is 4.1 MB against the debug's
71 MB (the debug carries unstripped native symbols).
| CameraX | 1.6.1 |
| libjpeg-turbo | vendored (ARM64 NEON SIMD only) |
