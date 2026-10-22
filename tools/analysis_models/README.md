# Multiframe analysis models

Multiframe's own opt-in models (Labs). Each *analyses* measured data and
returns a mask or a few numbers -- an illuminant, a subject's position, a
frame's blur, which region a pixel is -- and none returns a pixel value.
Each has a deterministic default doing the same job, is off until the user
turns it on, and is named in every photograph it touches (report and Content
Credentials). See REFERENCE §3.15 and the charter's "Choice over defaults".

| task | asset | label | where it comes from |
|---|---|---|---|
| `sky` | `sky_segmenter.tflite` | the astro stack's measured sky mask | Labs → Astro sky mask → COLLECT, shoot astro |
| `awb` | `awb_illuminant.tflite` | the white balance you set by hand in PRO | Labs → Analysis → COLLECT, shoot with manual WB |
| `subject` | `subject_saliency.tflite` | the point you tapped to focus | Labs → Analysis → COLLECT, tap to focus |
| `frame` | `frame_sharpness.tflite` | the gyro's rotation over each exposure × px/rad | Labs → Analysis → COLLECT, shoot |
| `regions` | `region_segmenter.tflite` | none on the phone | a dataset chosen by hand (licence: the project's call) |
| `person` | `person_segmenter.tflite` | none on the phone | a dataset chosen by hand (licence: the project's call) |

1. Collect on the phone (pairs stay on the phone until you copy them).
2. `adb pull /sdcard/Android/data/dev.multiframe.camera/files/training/<task> ./<task>`
3. `python train.py --task <task> --pairs ./<task>` (TensorFlow 2.16, CPU is enough),
   or for `regions`/`person`: `--images DIR --masks DIR`.
4. Copy the `.tflite` into `app/src/main/assets/` and rebuild. That task's Labs row appears.

`--smoke` checks the pipeline and the exported contract on synthetic data. Never ship a smoke model.

A pair (`.mfp`) is `MFP1`, the task code (a length byte and ASCII), the input's and
the label's width, height and channels (six LE int32), then both tensors as LE
float32, channels last: exactly what the phone feeds the model and expects back
(`TrainingPair` in `AnalysisModels.kt`).
