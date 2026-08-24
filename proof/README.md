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
