"""Train one of Multiframe's own opt-in analysis models, and export it.

Every model here *analyses* measured data and returns a mask or a few
numbers; none outputs a pixel value (CLAUDE.md, "Choice over defaults").
The tasks and their tensor contracts mirror `AnalysisTask` in
app/src/main/java/dev/multiframe/camera/pipeline/AnalysisModels.kt:

  task     input            output          label measured on the phone
  sky      256x192x1 luma   256x192x1 mask  the astro stack's measured sky mask
  awb      64x64x2 hist     1x1x2  (u, v)   the white balance set by hand in PRO
  subject  256x192x1 luma   64x48x1 heat    the point tapped to focus
  frame    256x192x1 crop   1x1x1  blur px  the gyro's rotation over the exposure
  regions  256x192x3 rgb    256x192x3 mask  (none: a dataset chosen by hand)
  person   256x192x3 rgb    256x192x1 mask  (none: a dataset chosen by hand)

Pairs the phone collected (Labs -> COLLECT TRAINING PAIRS) are MFP1 files
under Android/data/dev.multiframe.camera/files/training/<task>/:

    adb pull /sdcard/Android/data/dev.multiframe.camera/files/training/awb ./awb
    python train.py --task awb --pairs ./awb --out awb_illuminant.tflite
    cp awb_illuminant.tflite ../../app/src/main/assets/

`regions` and `person` have no label the phone can measure. They train from
--images/--masks directories (masks PNG, same base name; regions masks are
RGB = sky, skin, foliage). Which dataset, and its licence, is a decision for
the project, not this script.

--smoke trains on synthetic data to check the pipeline and the exported
tensor contract. A smoke model is not a real model; never ship one.
"""
import argparse
import glob
import os
import struct
import tempfile

import numpy as np

TASKS = {
    #          input (h, w, c)   output (h, w, c)  asset
    "sky":     ((192, 256, 1), (192, 256, 1), "sky_segmenter.tflite"),
    "awb":     ((64, 64, 2),   (1, 1, 2),     "awb_illuminant.tflite"),
    "subject": ((192, 256, 1), (48, 64, 1),   "subject_saliency.tflite"),
    "frame":   ((192, 256, 1), (1, 1, 1),     "frame_sharpness.tflite"),
    "regions": ((192, 256, 3), (192, 256, 3), "region_segmenter.tflite"),
    "person":  ((192, 256, 3), (192, 256, 1), "person_segmenter.tflite"),
}
# Left-right flips are honest augmentations of these (a sky is still up, a
# tap mirrored is still a subject); not of a histogram or a blur estimate.
FLIPPABLE = {"sky", "subject", "regions", "person", "frame"}


def read_pair(path, task):
    with open(path, "rb") as f:
        data = f.read()
    assert data[:4] == b"MFP1", f"{path}: not an MFP1 pair"
    n = data[4]
    code = data[5:5 + n].decode("ascii")
    assert code == task, f"{path}: a {code} pair, not {task}"
    p = 5 + n
    iw, ih, ic, ow, oh, oc = struct.unpack_from("<6i", data, p)
    p += 24
    (ish, osh, _) = TASKS[task]
    assert (ih, iw, ic) == ish and (oh, ow, oc) == osh, f"{path}: shapes do not match the contract"
    x = np.frombuffer(data, "<f4", ih * iw * ic, p).reshape(ish)
    y = np.frombuffer(data, "<f4", oh * ow * oc, p + ih * iw * ic * 4).reshape(osh)
    return x.astype(np.float32), y.astype(np.float32)


def model_input_from_image(rgb):
    """CfaThumbnail.modelInput, for a dataset's sRGB image: linear, area to 256x192, stretch by p99 luma, encode."""
    lin = (np.asarray(rgb, np.float32) / 255.0) ** 2.2
    h, w = lin.shape[:2]
    out = np.zeros((192, 256, 3), np.float32)
    for oy in range(192):
        y0, y1 = oy * h // 192, max(oy * h // 192 + 1, (oy + 1) * h // 192)
        for ox in range(256):
            x0, x1 = ox * w // 256, max(ox * w // 256 + 1, (ox + 1) * w // 256)
            out[oy, ox] = lin[y0:y1, x0:x1].reshape(-1, 3).mean(axis=0)
    luma = 0.2126 * out[..., 0] + 0.7152 * out[..., 1] + 0.0722 * out[..., 2]
    p99 = max(np.quantile(luma, 0.99), 1e-6)
    return np.clip(out / p99, 0, 1) ** (1 / 2.2)


def read_dataset(images, masks, task):
    from PIL import Image
    xs, ys = [], []
    oc = TASKS[task][1][2]
    for path in sorted(glob.glob(os.path.join(images, "*"))):
        base = os.path.splitext(os.path.basename(path))[0]
        mpath = os.path.join(masks, base + ".png")
        if not os.path.exists(mpath):
            continue
        xs.append(model_input_from_image(Image.open(path).convert("RGB")))
        m = Image.open(mpath).convert("RGB" if oc == 3 else "L").resize((256, 192), Image.BILINEAR)
        m = np.asarray(m, np.float32) / 255.0
        ys.append(m if oc == 3 else m[..., None])
    assert xs, "no image/mask pairs found"
    return np.array(xs), np.array(ys)


def synthetic(task, n, rng):
    ish, osh, _ = TASKS[task]
    xs = rng.uniform(0, 1, (n,) + ish).astype(np.float32)
    if task in ("sky", "regions", "person"):
        ys = np.zeros((n,) + osh, np.float32)
        for k in range(n):
            hz = rng.integers(48, 144)
            ys[k, :hz, :, 0] = 1.0
            xs[k, :hz] *= 0.2  # dark sky, bright ground
    elif task == "subject":
        ys = np.zeros((n,) + osh, np.float32)
        yy, xx = np.mgrid[0:48, 0:64]
        for k in range(n):
            cy, cx = rng.integers(8, 40), rng.integers(8, 56)
            ys[k, ..., 0] = np.exp(-((yy - cy) ** 2 + (xx - cx) ** 2) / (2 * 2.5 ** 2))
            xs[k, cy * 4 - 12:cy * 4 + 12, cx * 4 - 12:cx * 4 + 12] = 1.0
    elif task == "awb":
        ys = rng.uniform(0.2, 1.2, (n,) + osh).astype(np.float32)
        xs[:] = 0
        for k in range(n):
            iu = int((ys[k, 0, 0, 0] + 0.5) * 32); iv = int((ys[k, 0, 0, 1] + 0.5) * 32)
            xs[k, max(0, iv - 2):iv + 3, max(0, iu - 2):iu + 3, :] = 1.0 / 25
    else:  # frame
        blur = rng.uniform(0, 4, n).astype(np.float32)
        ys = blur.reshape((n,) + osh)
        for k in range(n):
            r = int(round(blur[k]))
            if r > 0:
                xs[k] = np.mean([np.roll(xs[k], d, axis=1) for d in range(-r, r + 1)], axis=0)
    return xs, ys


def build(task):
    import tensorflow as tf
    L = tf.keras.layers
    ish, osh, _ = TASKS[task]
    inp = L.Input(ish)

    def block(x, c):
        x = L.Conv2D(c, 3, padding="same", activation="relu")(x)
        return L.Conv2D(c, 3, padding="same", activation="relu")(x)

    if task in ("sky", "regions", "person"):
        e1 = block(inp, 12); p1 = L.MaxPool2D()(e1)
        e2 = block(p1, 24); p2 = L.MaxPool2D()(e2)
        e3 = block(p2, 48); p3 = L.MaxPool2D()(e3)
        b = block(p3, 64)
        d3 = block(L.Concatenate()([L.UpSampling2D()(b), e3]), 48)
        d2 = block(L.Concatenate()([L.UpSampling2D()(d3), e2]), 24)
        d1 = block(L.Concatenate()([L.UpSampling2D()(d2), e1]), 12)
        out = L.Conv2D(osh[2], 1, activation="sigmoid")(d1)
        loss = "binary_crossentropy"
    elif task == "subject":
        x = block(inp, 16); x = L.MaxPool2D()(x)
        x = block(x, 32); x = L.MaxPool2D()(x)  # 48x64
        x = block(x, 48)
        out = L.Conv2D(1, 1, activation="sigmoid")(x)
        loss = "binary_crossentropy"
    else:  # awb, frame: a few numbers
        x = inp
        for c in (16, 32, 64):
            x = L.Conv2D(c, 3, padding="same", activation="relu")(x); x = L.MaxPool2D()(x)
        x = L.GlobalAveragePooling2D()(x)
        x = L.Dense(64, activation="relu")(x)
        x = L.Dense(osh[2], activation="relu" if task == "frame" else None)(x)
        out = L.Reshape(osh)(x)
        loss = "mse"
    return tf.keras.Model(inp, out), loss


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--task", required=True, choices=sorted(TASKS))
    ap.add_argument("--pairs", help="directory of MFP1 pairs the phone collected")
    ap.add_argument("--images", help="regions/person: directory of images")
    ap.add_argument("--masks", help="regions/person: directory of PNG masks")
    ap.add_argument("--out")
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--smoke", action="store_true")
    a = ap.parse_args()
    import tensorflow as tf
    ish, osh, asset = TASKS[a.task]
    out_path = a.out or asset
    rng = np.random.default_rng(1)
    if a.smoke:
        x, y = synthetic(a.task, 192, rng)
    elif a.pairs:
        paths = sorted(glob.glob(os.path.join(a.pairs, "*.mfp")))
        assert paths, "no .mfp pairs found"
        pairs = [read_pair(p, a.task) for p in paths]
        x = np.array([p[0] for p in pairs]); y = np.array([p[1] for p in pairs])
    else:
        assert a.images and a.masks, "--pairs, or --images and --masks"
        x, y = read_dataset(a.images, a.masks, a.task)
    if not a.smoke and a.task in FLIPPABLE:
        x = np.concatenate([x, x[:, :, ::-1]])
        y = np.concatenate([y, y[:, :, ::-1]]) if y.shape[1] > 1 else np.concatenate([y, y])
    idx = rng.permutation(len(x)); split = max(1, len(x) // 10)
    val, train = idx[:split], idx[split:]
    m, loss = build(a.task)
    m.compile(optimizer=tf.keras.optimizers.Adam(1e-3), loss=loss)
    m.fit(x[train], y[train], validation_data=(x[val], y[val]),
          epochs=2 if a.smoke else a.epochs, batch_size=8, verbose=2)
    # Through a SavedModel: Keras 3's direct converter path fails on TF 2.16.
    saved = tempfile.mkdtemp()
    m.export(saved)
    tflite = tf.lite.TFLiteConverter.from_saved_model(saved).convert()
    with open(out_path, "wb") as f:
        f.write(tflite)
    # The contract the phone relies on: one float32 tensor in, one out, of these sizes.
    it = tf.lite.Interpreter(model_content=tflite); it.allocate_tensors()
    i, o = it.get_input_details()[0], it.get_output_details()[0]
    assert list(i["shape"]) == [1, *ish], (i["shape"], ish)
    assert int(np.prod(o["shape"])) == int(np.prod(osh)), (o["shape"], osh)
    assert i["dtype"] == np.float32 and o["dtype"] == np.float32
    it.set_tensor(i["index"], x[val][:1]); it.invoke()
    pred = it.get_tensor(o["index"]).reshape(osh)
    err = float(np.abs(pred - y[val][0]).mean())
    print(f"exported {out_path}: {len(tflite)/1024:.0f} KB, contract ok, sample mean abs error {err:.3f}")


if __name__ == "__main__":
    main()
