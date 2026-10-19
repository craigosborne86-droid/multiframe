#!/usr/bin/env python3
"""Temporal RAW diagnostics on fixed, explicitly named sensor-coordinate regions.

Temporal variance includes motion/lighting changes; mean-removed variance also
reported to expose that confound. No arbitrary-photo MTF or ground-truth SNR.
"""
import argparse
import csv
import json
from pathlib import Path
import numpy as np

p = argparse.ArgumentParser()
p.add_argument("archive", type=Path)
p.add_argument("replay", type=Path)
p.add_argument("output", type=Path)
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=True)
m = json.loads((a.archive / "manifest.json").read_text())
regions = {"dark-fabric": [2280, 2080, 400, 400], "carpet": [3040, 240, 400, 400],
           "cupboard": [80, 1850, 320, 360], "flat-cupboard-core": [100, 2010, 256, 192],
           "television-moving": [120, 80, 880, 820]}
(a.output / "regions.json").write_text(json.dumps(regions, indent=2) + "\n")
records = []
for shot in m["shots"]:
    if not shot["directory"].endswith("repeat0"):
        continue
    directory = a.archive / shot["directory"]
    frames = json.loads((directory / "frames.json").read_text())
    if not all("raw" in f for f in frames):
        continue
    raw = [np.memmap(directory / f["raw"], dtype="<u2", mode="r", shape=(m["height"], m["width"])) for f in frames]
    eff = np.memmap(a.replay / shot["directory"] / "effective-frames.f32", dtype="<f4", mode="r", shape=raw[0].shape)
    black = np.array(frames[0]["black"])
    model = np.array(frames[0]["noiseProfile"])
    for name, (x, y, w, h) in regions.items():
        stack = np.stack([im[y:y+h, x:x+w] for im in raw]).astype(np.float64)
        for site in range(4):
            samples = stack[:, site // 2::2, site % 2::2]
            mean = samples.mean(axis=0)
            variance = samples.var(axis=0, ddof=1)
            global_mean = samples.mean(axis=(1, 2))
            detrended = samples - global_mean[:, None, None]
            # DNG linear-reference convention: x=(DN-B)/(W-B). Affine
            # variance transforms as Var(aX+b)=a² Var(X), not as aX+b.
            signal_range = frames[0]["white"] - black[site]
            prediction = model[site, 0] * np.maximum(mean - black[site], 0) * signal_range + model[site, 1] * signal_range ** 2
            actual = float(detrended.var(axis=0, ddof=1).mean())
            records.append(dict(burst=shot["directory"], iso=frames[0]["iso"], region=name,
                site=site, cfa=m["cfa"][site], meanDn=float(mean.mean()),
                temporalVarianceDn2=float(variance.mean()), detrendedVarianceDn2=actual,
                frameMeanStdDn=float(global_mean.std(ddof=1)), predictedVarianceDn2=float(prediction.mean()),
                measuredOverPredicted=actual/float(prediction.mean()),
                effectiveFramesMean=float(eff[y:y+h, x:x+w][site//2::2, site%2::2].mean())))
with (a.output / "temporal-noise.csv").open("w") as out:
    writer = csv.DictWriter(out, fieldnames=records[0]); writer.writeheader(); writer.writerows(records)
(a.output / "README.md").write_text("""These are frozen, real Pixel RAW samples, not a calibrated flat-field capture.
All variance is temporal and measured before lens shading, WB and tone. Fixed
pattern that does not vary across the burst is not included. Static-region
lighting/motion remains a possible contaminant; TV region is deliberately moving.
The flat cupboard core was selected by visual geometry before inspecting its
variance. These ratios audit consistency, not metrological validation of Camera2.
Lens shading/exposure scaling would multiply both measured and predicted variance
by gain squared. No Camera2 model has been put into production by this script.
""")
for r in records:
    if r["region"] == "flat-cupboard-core":
        print(r["iso"], r["site"], "measured/predicted", round(r["measuredOverPredicted"], 3),
              "frame drift DN", round(r["frameMeanStdDn"], 3))
