# Handover

A short brief for picking this up in a fresh session. Delete it once it has
served its purpose; [SESSION-LOG.md](SESSION-LOG.md) is the real record and
holds the reasoning behind everything below.

## Where things stand

`main` is at `3ab2ee5`, the working tree is clean, and everything builds. **332
unit tests and 92 device tests pass** on a Pixel 9 Pro XL running Android 17.

Every row of [SUPERRES.md](SUPERRES.md) is built. Phases 6, 7 and 8 are closed
with measurements on the phone.

## How this repo works

Four rules, all of them earned rather than assumed, and worth keeping:

- **Every C++ stage is pinned to a Kotlin reference by a parity test.** Develop,
  sharpening, shading, alignment, rotation and now the merge. The Kotlin is the
  reference implementation; the native one is held to it.
- **A speed claim needs non-overlapping ranges, not a better mean.** This log
  has withdrawn claims that sat inside the device's run-to-run spread.
- **No timing is ever taken from the emulator.** It settles framework behaviour,
  never performance.
- **Negative results are recorded and reverted**, not kept on faith. Two
  experiments were backed out this way: a lower-priority DNG writer thread, and
  disabling filtering on the rotation.

A recurring lesson, hit three times: a single reported figure often bundles two
very different things. Splitting the timer before optimising found that
alignment was 83% of the "merge", and that the JPEG encode — not the demosaic —
is the largest item inside "develop".

## Next step

In `app/src/main/cpp/bayer_merge.cpp`, in the accumulate loop of `nAddFrame`:
hoist the bounds test and the source-row pointer from the pixel to the tile run.
Within a run of pixels sharing a tile the displacement is constant, so both are
invariant across it. Only after that is there any point reaching for NEON.

`DevelopParityTest.nativeAndKotlinMergeAgree` is the safety net and passes now,
so a change that breaks the arithmetic will be caught.

## Environment

- **WiFi adb has degraded** to roughly five minutes per APK install, which makes
  Gradle look hung. Use USB, or install once by hand and drive tests with
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

## Two open decisions

- **The rotation.** Now 69 ms after being tiled. It could go to zero by not
  rotating pixels at all and writing an EXIF orientation tag instead — but that
  changes what the file *is*, trading the time for a dependence on every viewer
  honouring the tag, which is precisely what this app rotates in order not to
  need.
- **The JPEG encode**, around 240 ms and the largest single item in a capture.
  It is not wasteful — 12.5 MP at roughly 52 MP/s through libjpeg-turbo is about
  what that costs — so the options are quality, a different encoder, or leaving
  it alone.
