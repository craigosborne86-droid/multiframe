#!/usr/bin/env python3
"""Generate explicit Java-readable replay metadata from a hashed RAW archive.

The DNG supplies this physical sensor's calibration, never replacement capture
metadata. WB, exposure and shading are taken from each archived CaptureResult.
"""
import argparse
import json
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument("archive", type=Path)
p.add_argument("calibration_dng", type=Path)
p.add_argument("output", type=Path)
a = p.parse_args()
m = json.loads((a.archive / "manifest.json").read_text())
for shot in m["shots"]:
    src = a.archive / shot["directory"]
    frames = json.loads((src / "frames.json").read_text())
    if not all("raw" in f for f in frames):
        continue
    out = a.output / shot["directory"]
    out.mkdir(parents=True, exist_ok=True)
    ref = frames[0]
    fields = dict(width=m["width"], height=m["height"], white=m["staticWhite"],
                  black=m["staticBlack"], cfa=m["cfa"], iso=ref["iso"],
                  wb=ref["wbRggb"], neutral=ref["neutral"],
                  shadingColumns=ref["shadingColumns"], shadingRows=ref["shadingRows"],
                  shadingRggb=ref["shadingRggb"],
                  calibration=str(a.calibration_dng.resolve()), frames=len(frames))
    for i, f in enumerate(frames):
        fields[f"frame.{i}.path"] = str((src / f["raw"]).resolve())
        fields[f"frame.{i}.sha256"] = f["sha256"]
        fields[f"frame.{i}.light"] = f["iso"] * f["exposureNs"]
        fields[f"frame.{i}.timestamp"] = f["imageTimestamp"]
        fields[f"frame.{i}.noiseProfile"] = [v for pair in f.get("noiseProfile", []) for v in pair]
        fields[f"frame.{i}.black"] = f.get("black", m["staticBlack"])
        fields[f"frame.{i}.white"] = f.get("white", m["staticWhite"])
        associated = next((r for r in frames if r["resultTimestamp"] == f.get("snapshotResultTimestamp")), None)
        if associated:
            fields[f"frame.{i}.baselineLight"] = associated["iso"] * associated["exposureNs"]
    (out / "replay.properties").write_text("\n".join(
        f"{key}={','.join(map(str, value)) if isinstance(value, list) else value}"
        for key, value in fields.items()) + "\n")
    print(out)
