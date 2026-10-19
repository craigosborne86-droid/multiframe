#!/usr/bin/env python3
"""Reproduce canvas-sharpen band races on an arm64 host using actual C++ source.

Requires clang++ and NumPy. This is a deterministic correctness check; it makes
no Android/Pixel latency claim and does not run Gradle or modify production code.

Run the current implementation:
  python3 tools/iq_forensics/check_sharpen_bands.py --out /tmp/sharpen-check

Reproduce the archived failure (snapshot contains *.before source files):
  python3 tools/iq_forensics/check_sharpen_bands.py --out /tmp/sharpen-before \
    --before-snapshot work/iq-forensics-20260927-183625/sharpen-race --expect race
"""

import argparse
import csv
import hashlib
import json
from pathlib import Path
import platform
import shutil
import subprocess
import sys

import numpy as np

FROZEN_INPUT_SHA = "a50c0f080214c08e32ac62b8e29c01c9de51be789794042b1535a79f5a54e66c"
FROZEN_REFERENCE_SHA = "482342543522edf1f735b07f2c5d97087942c405935a01adec39745140955059"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--expect", choices=("fixed", "race"), default="fixed")
    parser.add_argument("--before-snapshot", type=Path)
    parser.add_argument("--native-dir", type=Path,
                        help="Override production source directory for an isolated checkout")
    args = parser.parse_args()
    if platform.machine().lower() not in ("arm64", "aarch64"):
        raise SystemExit("The actual native translation unit requires an arm64 host; no scalar surrogate is used.")
    repo = Path(__file__).resolve().parents[2]
    native = (args.native_dir or repo / "app/src/main/cpp").resolve()
    harness = Path(__file__).with_name("sharpen_bands_check.cpp").resolve()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    if list(out.glob("run*.rgb8")):
        raise SystemExit("Output contains previous runs; choose a fresh output directory to preserve evidence.")
    if args.before_snapshot:
        original = native
        native = out / "native-source"
        native.mkdir()
        for name in ("LinearDevelop.cpp", "LinearDevelop.h", "Tone.h", "Chroma.h", "Jpeg.h"):
            before = args.before_snapshot.resolve() / (name + ".before")
            shutil.copy2(before if before.exists() else original / name, native / name)
    source_hashes = {name: sha(native / name) for name in
                     ("LinearDevelop.cpp", "LinearDevelop.h", "Tone.h", "Chroma.h", "Jpeg.h")}
    source_hashes["sharpen_bands_check.cpp"] = sha(harness)
    source_hashes["check_sharpen_bands.py"] = sha(Path(__file__).resolve())
    (out / "source-hashes.json").write_text(json.dumps(source_hashes, indent=2) + "\n")
    include = out / "include/android"
    include.mkdir(parents=True, exist_ok=True)
    (include / "log.h").write_text(
        "#pragma once\n#define ANDROID_LOG_INFO 4\n#define ANDROID_LOG_WARN 5\n"
        "#define ANDROID_LOG_ERROR 6\ninline int __android_log_print(int, const char*, const char*, ...) { return 0; }\n")
    binary = out / "sharpen_bands_check"
    command = ["clang++", "-std=c++20", "-O3", "-ffast-math", "-pthread",
               "-I" + str(out / "include"),
               '-DMF_LINEAR_DEVELOP_SOURCE="' + str(native / "LinearDevelop.cpp") + '"',
               str(harness), "-o", str(binary)]
    (out / "build-command.json").write_text(json.dumps(command, indent=2) + "\n")
    compiler = subprocess.run(["clang++", "--version"], check=True, text=True,
                              capture_output=True).stdout
    (out / "compiler.txt").write_text(compiler)
    subprocess.run(command, check=True)
    subprocess.run([str(binary), str(out)], check=True)
    meta = json.loads((out / "metadata.json").read_text())
    shape = (meta["height"], meta["width"], 3)
    reference = np.fromfile(out / "reference.rgb8", np.uint8).reshape(shape)
    original = np.fromfile(out / "input.rgb8", np.uint8).reshape(shape)
    borders = np.zeros(shape[:2], bool)
    borders[0] = borders[-1] = True
    borders[:, 0] = borders[:, -1] = True
    boundaries = np.zeros(shape[0], bool)
    for row in range(meta["bandRows"], shape[0], meta["bandRows"]):
        boundaries[row - 1:row + 1] = True
    runs = []
    for path in sorted(out.glob("run*.rgb8")):
        actual = np.fromfile(path, np.uint8).reshape(shape)
        diff = np.abs(actual.astype(np.int16) - reference.astype(np.int16))
        ys, xs, channels = np.nonzero(diff)
        runs.append(dict(run=path.stem, sha256=sha(path),
                         changedChannels=int(np.count_nonzero(diff)),
                         maxCodeError=int(diff.max()),
                         boundaryChanged=int(np.count_nonzero(diff[boundaries])),
                         nonBoundaryChanged=int(np.count_nonzero(diff[~boundaries])),
                         maxBoundaryError=int(diff[boundaries].max()),
                         maxNonBoundaryError=int(diff[~boundaries].max()),
                         changedBorders=int(np.count_nonzero(actual[borders] != original[borders])),
                         firstChanged=[int(xs[0]), int(ys[0]), int(channels[0])] if len(ys) else None,
                         changedRows=np.unique(ys).tolist()))
    with (out / "checks.csv").open() as stream:
        checks = list(csv.DictReader(stream))
    for row in checks:
        for key in row.keys() - {"case", "referenceAANull"}:
            row[key] = int(row[key])
        row["referenceAANull"] = row["referenceAANull"] == "true"
        row["passed"] = (row["maxCodeError"] <= 1 and row["uniqueOutputs"] == 1
                         and row["referenceAANull"] and not any(row[key] for key in
                         ("changedGuards", "changedPadding", "changedBorders", "disabledChanges")))
        row["outputSha256s"] = sorted({sha(path) for path in
                                        (out / "checks").glob(row["case"] + "-run*.bin")})
    input_hash, reference_hash = sha(out / "input.rgb8"), sha(out / "reference.rgb8")
    frozen = input_hash == FROZEN_INPUT_SHA and reference_hash == FROZEN_REFERENCE_SHA
    unique = len({row["sha256"] for row in runs})
    max_error = max(row["maxCodeError"] for row in runs)
    border_changes = max(row["changedBorders"] for row in runs)
    controls_ok = (frozen and meta["referenceAANull"] and not border_changes
                   and all(row["referenceAANull"] and not any(row[key] for key in
                   ("changedGuards", "changedPadding", "changedBorders", "disabledChanges"))
                   for row in checks))
    fixed = unique == 1 and max_error <= 1 and all(row["passed"] for row in checks)
    race = unique > 1 or max_error > 1
    accepted = controls_ok and (fixed if args.expect == "fixed" else race)
    report = dict(domain="Actual host arm64 C++ correctness; synthetic frozen RGB, no Pixel or timing claim",
                  expected=args.expect, accepted=accepted, metadata=meta,
                  inputSha256=input_hash, referenceSha256=reference_hash,
                  frozenFixtureIdentical=frozen, uniqueParallelOutputs=unique,
                  maxCodeError=max_error, changedBorders=border_changes,
                  checksPassed=sum(row["passed"] for row in checks),
                  checksTotal=len(checks), cases=checks, runs=runs)
    (out / "comparison.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({key: report[key] for key in
                      ("expected", "accepted", "frozenFixtureIdentical", "uniqueParallelOutputs",
                       "maxCodeError", "changedBorders", "checksPassed", "checksTotal")}, indent=2))
    return 0 if accepted else 1


if __name__ == "__main__":
    sys.exit(main())
