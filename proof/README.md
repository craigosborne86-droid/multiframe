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
