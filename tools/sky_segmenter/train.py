"""Train Multiframe's own sky segmenter from pairs the app collected, and export it.

Pairs come from the app's Labs switch "COLLECT TRAINING PAIRS": each astro
capture writes pair_<ms>.skp under Android/data/dev.multiframe.camera/files/
sky-training/ -- the reference frame, preprocessed exactly as the phone feeds
the model (SkyModelContract: 256x192, stretched to its 99th percentile), and
the *measured* sky mask the stack derived. Copy them off with

    adb pull /sdcard/Android/data/dev.multiframe.camera/files/sky-training ./pairs

then

    python train.py --pairs ./pairs --out sky_segmenter.tflite
    cp sky_segmenter.tflite ../../app/src/main/assets/

The model only ever outputs a probability per pixel that the pixel is sky.
The app uses it to choose between two stacks of measured samples; it never
produces a pixel value (CLAUDE.md, "Choice over defaults").

--smoke trains on synthetic scenes to check the pipeline and the exported
tensor contract. A smoke model is not a real model; never ship one.
"""
import argparse
import glob
import os
import struct

import numpy as np

W, H = 256, 192


def read_pair(path):
    with open(path, "rb") as f:
        magic, w, h = struct.unpack("<4sii", f.read(12))
        assert magic == b"SKP1" and (w, h) == (W, H), f"{path}: not a SKP1 {W}x{H} pair"
        x = np.frombuffer(f.read(w * h), np.uint8).reshape(h, w, 1) / 255.0
        y = np.frombuffer(f.read(w * h), np.uint8).reshape(h, w, 1) / 255.0
    return x.astype(np.float32), y.astype(np.float32)


def synthetic(n, rng):
    """Sky (dark, sparse stars) over ground (textured) with a wandering horizon."""
    xs, ys = [], []
    for _ in range(n):
        horizon = rng.integers(H // 4, 3 * H // 4) + (rng.normal(0, 8, W).cumsum() * 0.2).astype(int)
        img = np.zeros((H, W), np.float32)
        mask = np.zeros((H, W), np.float32)
        for x in range(W):
            hy = int(np.clip(horizon[x], 4, H - 4))
            mask[:hy, x] = 1.0
            img[:hy, x] = rng.uniform(0.02, 0.08)
            img[hy:, x] = rng.uniform(0.1, 0.6, H - hy)
        stars = rng.integers(0, W * H, 300)
        sky_stars = stars[mask.ravel()[stars] > 0.5]
        img.ravel()[sky_stars] = rng.uniform(0.5, 1.0, len(sky_stars))
        img = np.clip(img / max(np.quantile(img, 0.99), 1e-3), 0, 1)
        xs.append(img[..., None]); ys.append(mask[..., None])
    return np.array(xs), np.array(ys)


def model():
    import tensorflow as tf
    L = tf.keras.layers
    inp = L.Input((H, W, 1))
    def block(x, c):
        x = L.Conv2D(c, 3, padding="same", activation="relu")(x)
        return L.Conv2D(c, 3, padding="same", activation="relu")(x)
    e1 = block(inp, 12); p1 = L.MaxPool2D()(e1)
    e2 = block(p1, 24); p2 = L.MaxPool2D()(e2)
    e3 = block(p2, 48); p3 = L.MaxPool2D()(e3)
    b = block(p3, 64)
    u3 = L.Concatenate()([L.UpSampling2D()(b), e3]); d3 = block(u3, 48)
    u2 = L.Concatenate()([L.UpSampling2D()(d3), e2]); d2 = block(u2, 24)
    u1 = L.Concatenate()([L.UpSampling2D()(d2), e1]); d1 = block(u1, 12)
    out = L.Conv2D(1, 1, activation="sigmoid")(d1)
    return tf.keras.Model(inp, out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pairs")
    ap.add_argument("--out", default="sky_segmenter.tflite")
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--smoke", action="store_true")
    a = ap.parse_args()
    import tensorflow as tf
    rng = np.random.default_rng(1)
    if a.smoke:
        x, y = synthetic(256, rng)
    else:
        paths = sorted(glob.glob(os.path.join(a.pairs, "*.skp")))
        assert paths, "no .skp pairs found"
        pairs = [read_pair(p) for p in paths]
        x = np.array([p[0] for p in pairs]); y = np.array([p[1] for p in pairs])
        # Flips are honest augmentations of a sky; rotations are not (the sky is up).
        x = np.concatenate([x, x[:, :, ::-1]]); y = np.concatenate([y, y[:, :, ::-1]])
    idx = rng.permutation(len(x)); split = max(1, len(x) // 10)
    val, train = idx[:split], idx[split:]
    m = model()
    m.compile(optimizer=tf.keras.optimizers.Adam(1e-3), loss="binary_crossentropy",
              metrics=[tf.keras.metrics.BinaryIoU(target_class_ids=[1], threshold=0.5)])
    m.fit(x[train], y[train], validation_data=(x[val], y[val]),
          epochs=3 if a.smoke else a.epochs, batch_size=8, verbose=2)
    # Through a SavedModel: Keras 3's direct converter path fails on TF 2.16.
    import tempfile
    saved = tempfile.mkdtemp()
    m.export(saved)
    tflite = tf.lite.TFLiteConverter.from_saved_model(saved).convert()
    with open(a.out, "wb") as f:
        f.write(tflite)
    # Check the exported contract the phone relies on.
    it = tf.lite.Interpreter(model_content=tflite); it.allocate_tensors()
    i, o = it.get_input_details()[0], it.get_output_details()[0]
    assert list(i["shape"]) == [1, H, W, 1] and list(o["shape"]) == [1, H, W, 1], (i["shape"], o["shape"])
    assert i["dtype"] == np.float32 and o["dtype"] == np.float32
    it.set_tensor(i["index"], x[val][:1]); it.invoke()
    pred = it.get_tensor(o["index"])
    iou = ((pred > 0.5) & (y[val][:1] > 0.5)).sum() / max(1, ((pred > 0.5) | (y[val][:1] > 0.5)).sum())
    print(f"exported {a.out}: {len(tflite)/1024:.0f} KB, contract ok, sample IoU {iou:.3f}")


if __name__ == "__main__":
    main()
