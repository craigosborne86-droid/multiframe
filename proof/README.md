# Phase 1 on-device evidence

Device: Pixel 9 Pro XL (komodo), Android 17 / API 37, serial 47251FDAS009N9

| File | What it shows |
|---|---|
| `p1_permission.png` | Runtime CAMERA permission dialog, app name rendering correctly |
| `p1_viewfinder.png` | Live viewfinder rendering real camera content + shutter button |
| `MF_20260824_082024.jpg` | Capture saved by the app, pulled back off the device |
| `capture_exif_applied.jpg` | Same capture with EXIF orientation applied |

## Orientation

The scene looks rotated in these images. It is not a pipeline fault. At capture
time the phone was physically lying on its side — the accelerometer read
x=-9.72, y=0.22 m/s^2, i.e. gravity almost entirely on X — while the system had
auto-rotate disabled (`accelerometer_rotation=0`, `USER_ROTATION_LOCKED`,
`ROTATION_0`). Preview, JPEG pixels, EXIF `Orientation=6` and the MediaStore
`orientation=90` column all agree with each other.

Still worth fixing later: the app should drive `ImageCapture.targetRotation`
from an `OrientationEventListener` so captures come out upright even when the
UI is locked to portrait. Tracked for Phase 3/4.
