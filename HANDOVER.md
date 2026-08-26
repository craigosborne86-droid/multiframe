# Handover

A short brief for picking this up in a fresh session. Delete it once it has
served its purpose; [SESSION-LOG.md](SESSION-LOG.md) is the real record and
holds the reasoning behind everything below.

## Where things stand

Everything builds. **332 unit tests pass**, and **102 device tests** run on a
Pixel 9 Pro XL on Android 17.

Every row of [SUPERRES.md](SUPERRES.md) is built. Phases 6, 7 and 8 are closed
with measurements on the phone.

This session split the develop timer and acted on what it showed:

- **the develop is five stages, four of which had never been timed.** Naming
  them found that the demosaic is 130 ms of a 296 ms native develop, not most
  of it — and that **capture sharpening was 93 ms**, a third of the native
  develop and the second largest item in a whole capture, reported nowhere
- **sharpening now writes in place and runs four pixels at a time in NEON**, for
  85-103 ms down to 54-65. Bit-identical: the parity test still reports worst
  0/255 against the Kotlin reference
- two harnesses added, `SharpenSpeedDeviceTest` and `DevelopSpeedDeviceTest`,
  because the capture path could not resolve the change (see below)

`test-photos`: instrumentation runs write real captures into
`/sdcard/DCIM/Multiframe/`, and roughly 60 have accumulated. They are the
user's to delete.

## How this repo works

Six rules, all of them earned rather than assumed, and worth keeping:

- **Every C++ stage is pinned to a Kotlin reference by a parity test.** Develop,
  sharpening, shading, alignment, rotation and the merge. The Kotlin is the
  reference implementation; the native one is held to it.
- **A speed claim needs non-overlapping ranges, not a better mean.** This log
  has withdrawn claims that sat inside the device's run-to-run spread.
- **That rule is also a statement about the harness.** The sharpening change was
  first measured through real captures, correctly, and the pooled ranges still
  overlapped — a capture runs the camera, the merge, a DNG writer and a
  MediaStore publish over the same cores. Isolating the pass separated them. If
  the ranges overlap, ask what the instrument can see before concluding
  anything.
- **A null result needs a harness that could have seen the effect**, and a
  parity test needs a fixture that could have shown the difference. The develop
  parity test now renders each width a second time with sharpening off and
  asserts the two differ, so it cannot pass by sharpening nothing.
- **No timing is ever taken from the emulator.** It settles framework behaviour,
  never performance.
- **Third-party code is a last resort, and there is exactly one piece of it.**
  libjpeg-turbo is vendored under `cpp/third_party/`, pinned to the framework
  encoder's output rather than to a Kotlin reference, because it cannot be.
- **Negative results are recorded and reverted**, not kept on faith. Four
  experiments have been backed out this way: a lower-priority DNG writer thread,
  disabling filtering on the rotation, aligning natively inside the merge
  benchmark, and interleaving the merge's two accumulation planes.

A recurring lesson, hit five times now: a single reported figure often bundles
two very different things. Splitting the timer before optimising found that
alignment was 83% of the "merge", that the JPEG encode — not the demosaic — was
the largest item inside "develop", and this session that sharpening was a third
of what was left.

## Next step

**Take a rested capture reading first.** The phone degraded during this session
and never recovered: a run that measured 496-628 ms of develop in the morning
measured 866-912 ms at the end, on every stage including the JPEG encode and
the MediaStore publish, which nothing touched. So the post-sharpening figure for
a whole capture has not been measured on a healthy phone. It should be about
260 ms of native develop, but that is arithmetic, not a measurement.

**Then the render, at 199 ms on a rested phone, is the largest item.** It splits:

    black 15ms, hotpixels 14ms, shading 38ms, demosaic+tone 130ms

- **`demosaic+tone` is still a bundle**, and this log has been wrong five times
  about which half of a bundle held the time. Split it before optimising. It
  cannot be done with a timer — it is one fused loop — so it needs an ablation
  build, the way the sharpening before-and-after was built: keep the demosaic
  and replace the tone stage with a direct byte write, alternate against the
  shipping build, take the difference. Keep the demosaic result used or the
  compiler will delete it.
- **`shading` at 38 ms is the cheapest real win.** `shadingGain` runs per pixel
  and does two float divides, four clamps and four gathers into the map, for a
  grid whose cells are 240 pixels wide. The x-dependent parts (`fx`, `x0`, `tx`)
  depend only on x and could be computed once for the whole image; within a
  cell, the four corner values are loop-invariant. Both are exact
  rearrangements — no reassociation — so parity survives.
- **`black` and `hotpixels` are two passes over the same 50 MB plane** with the
  hot-pixel scan between them only because the correction has to happen in the
  sensor's own domain, before shading. Fusing hot pixels with shading is
  possible with a two-row lag, but band boundaries make it fiddly and it is
  worth maybe 15-20 ms.

Do not start by assuming the demosaic is the cost. That assumption has now been
wrong twice here — the JPEG encode the first time, sharpening the second.

If you come back to `nAddFrame`, two things are settled: **interleaving `sum`
and `weight` has been tried and reverted** (see the log), and **the noise lookup
is why the compiler never vectorised the loop** — `noise[refRaw]` is a
data-dependent load and NEON has no gather. Folding it into a `vqtbl4q_u8`
lookup would need the bin computed in vector form, reproducing `binOf` exactly.

## How to measure

Three instruments, in decreasing order of authority:

- **The real capture path is the only authority on what a photograph costs.**
  Run `ZslStreamDeviceTest#repeatedCapturesTakeAConsistentTime` and read
  `develop breakdown`, `develop stages`, `develop:` and `sharpen:` out of
  logcat. That is real camera frames.
- **`SharpenSpeedDeviceTest` and `DevelopSpeedDeviceTest` isolate a stage**, and
  are comparison instruments only. The develop one **reads about twice what a
  capture pays and nobody knows why** — clock ramp, exposure and foreground
  scheduling were each tried as explanations and each failed. It is repeatable
  to about 2% (medians of 406, 414, 415 across three runs), which is what makes
  it useful for before-and-after. Never quote it as what a capture costs.
- `MergeSpeedDeviceTest` times the accumulation, and aligns on the JVM where a
  capture aligns natively, so it reads high. Switching it to `alignNative` is
  still the obvious improvement and still has not been done.

Hard-won notes that still apply:

- **Alternate the two builds several times, not once.** Push both APKs to
  `/data/local/tmp` and `adb shell pm install -r` from there: about twenty
  seconds against three minutes for a wireless install, which is what makes four
  alternations affordable. To build a before-APK that shares a new harness,
  splice the old implementation back into the current tree, build, save the APK,
  then restore — that is how this session's sharpening comparison was made.
- **The device degrades over an afternoon, and a short rest does not fix it.**
  Watch `dumpsys battery` and `/proc/meminfo`, and know that both can look fine
  while everything runs at half speed.
- **Never subtract a figure in the log from one taken in a different run.** Only
  an alternated comparison inside a single session means anything.
- **Wireless adb drops writes** under this load: installs fail with "device
  offline" and then succeed on a retry. Loop the install two or three times.

## Environment

- **WiFi adb has degraded** to roughly three minutes per APK install, which
  makes Gradle look hung. Use USB, or install once by hand and drive tests with
  `adb shell am instrument -w -e class <Class> dev.multiframe.camera.test/androidx.test.runner.AndroidJUnitRunner`.
- The phone connects over mDNS; watch for it attaching **twice** (once by IP,
  once by mDNS), which makes every `adb` command fail with "more than one
  device".
- Emulator AVD lives at `/Volumes/MultiframeAVD`, an APFS sparse image on the
  external drive — **exFAT cannot host an AVD**, it has no hard links. Attach it
  with `hdiutil attach` before starting the emulator.
- The Compose tests skip when the phone's screen locks. That is by design, not a
  failure.
- Build output is redirected off the exFAT project volume; see
  [BUILD.md](BUILD.md).

## What needs you rather than me

- **Nobody has swept a real scene with the mosaic.** Every stitching claim rests
  on synthetic frames cut from a generated image. That is the right way to test
  the algorithm and no substitute for pointing it at a building.
- The release checklist in the session log: privacy policy, release keystore,
  Play Data safety form, store screenshots, and a proper trademark clearance on
  whichever name ships. [NAMES.md](NAMES.md) recommends *Coadd* over the working
  name and explains why.

## Three open decisions

- **The rotation.** 69 ms after being tiled. It could go to zero by not rotating
  pixels at all and writing an EXIF orientation tag instead — but that changes
  what the file *is*, trading the time for a dependence on every viewer honouring
  the tag, which is precisely what this app rotates in order not to need.
- **JPEG file size.** The strip encoder writes files about 5% larger than the
  framework did, because every strip has to share one Huffman table set and so
  it cannot do the second pass that fits the tables to the image. The pixels are
  identical; only the byte count differs. Worth revisiting only if size ever
  matters more than the three times speedup it bought.
- **Quality is still 95, and lowering it would not buy speed** — measured, on
  photograph-like content the encode time barely moves between q95 and q75 while
  the file halves. It remains a size decision, and the DNG written alongside is
  the archival copy, so q90 would be defensible.
