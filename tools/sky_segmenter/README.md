# Multiframe sky segmenter

Multiframe's own opt-in model for astro's sky mask (Labs). It is trained only on
pairs the app collects from tripod astro captures, where the label is the
mask the stack *measured* from which pixels moved with the stars. That means
no third-party dataset and no licence question. See REFERENCE §3.13 and the
charter's "Choice over defaults".

1. On the phone: Settings → Labs → Astro sky mask → COLLECT TRAINING PAIRS: ON, then shoot astro.
2. `adb pull /sdcard/Android/data/dev.multiframe.camera/files/sky-training ./pairs`
3. `python train.py --pairs ./pairs --out sky_segmenter.tflite` (TensorFlow 2.16, CPU is enough)
4. `cp sky_segmenter.tflite ../../app/src/main/assets/` and rebuild. The Labs "SKY MASK" row appears.

`--smoke` checks the pipeline on synthetic scenes. Never ship a smoke model.

Pairs are 12-byte header `SKP1`, width, height (LE int32), then 256×192 input bytes, then
256×192 label bytes. Input preprocessing is `SkyModelContract.preprocess`; the model
must output sky probability with the same shape.
