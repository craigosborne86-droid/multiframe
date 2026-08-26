# Handover

A short brief for picking this up in a fresh session. Delete it once it has
served its purpose; [SESSION-LOG.md](SESSION-LOG.md) is the real record and
holds the reasoning behind everything below.

## Where things stand

Everything builds. **332 unit tests pass**, and **95 device tests** run on a
Pixel 9 Pro XL on Android 17.

Every row of [SUPERRES.md](SUPERRES.md) is built. Phases 6, 7 and 8 are closed
with measurements on the phone.

The merge accumulation now walks tile runs rather than pixels, which took the
per-frame accumulate from 71 ms to 52 on real captures.

## How this repo works

Five rules, all of them earned rather than assumed, and worth keeping:

- **Every C++ stage is pinned to a Kotlin reference by a parity test.** Develop,
  sharpening, shading, alignment, rotation and the merge. The Kotlin is the
  reference implementation; the native one is held to it.
- **A speed claim needs non-overlapping ranges, not a better mean.** This log
  has withdrawn claims that sat inside the device's run-to-run spread.
- **A null result needs a harness that could have seen the effect.** This is the
  newest rule and was learned the expensive way: nine samples across two
  installs reported the run-hoisting change as a 3% nothing. Twenty-five samples
  across four found 21%, and real captures found 28%. Before reporting that
  something changed nothing, say what the measurement could resolve.
- **No timing is ever taken from the emulator.** It settles framework behaviour,
  never performance.
- **Negative results are recorded and reverted**, not kept on faith. Three
  experiments have been backed out this way: a lower-priority DNG writer thread,
  disabling filtering on the rotation, and aligning natively inside the merge
  benchmark.

A recurring lesson, hit four times now: a single reported figure often bundles
two very different things. Splitting the timer before optimising found that
alignment was 83% of the "merge", and that the JPEG encode — not the demosaic —
is the largest item inside "develop".

## Next step

Two, in the order they are worth trying, both in `nAddFrame`:

1. **`sum` and `weight` are separate 50 MB planes**, so every pixel touches two
   cache lines far apart and each thread keeps four streams going. Interleaving
   them into one plane of pairs would halve the streams and put both values a
   pixel needs on one line. It touches `nSetReference` and `nFinish` too.
2. **NEON**, which is finally worth reaching for now that the inner loop is a
   flat span of pixels with no branch in it.

`DevelopParityTest.nativeAndKotlinMergeAgree` and
`nativeAndKotlinMergeAgreeOnAVaryingField` are the safety net and both pass, so
a change that breaks the arithmetic will be caught. The second is the one that
matters for anything touching the run structure: it hands both implementations
the same tile-varying displacements, including a tile displaced out of frame.

**Before re-measuring, reboot the phone.** See below.

## How to measure the merge

`MergeSpeedDeviceTest` times the accumulation over a burst, with an unchanged
pass timed beside it as a control. Some hard-won notes:

- **Alternate the two builds several times, not once.** Push both APKs to
  `/data/local/tmp` and `adb shell pm install -r` from there: twenty seconds
  against three minutes for a wireless install, which is what makes four
  alternations affordable.
- **The device degrades over an afternoon.** By the end of the session that
  produced this, free memory was 1.2 GB with swap nearly gone and the battery
  at 39 C, and the identical binary that had measured a 254 ms burst measured
  466. Reboot before a measuring run, and watch `dumpsys battery` and
  `/proc/meminfo` alongside the numbers.
- **The real capture path is better evidence than the harness**, and it is
  cheap: run `ZslStreamDeviceTest` and read `merge breakdown ... accumulate` out
  of logcat. That is real camera frames, and it is the same quantity every
  accumulate figure in the log has been.
- `MergeSpeedDeviceTest` aligns on the JVM where a capture aligns natively, so
  it reads high — 44 MB of garbage a burst, collected on the cores doing the
  accumulating. Switching it to `alignNative` is the obvious improvement and
  could not be evaluated on a phone this tired.

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

## Two open decisions

- **The rotation.** 69 ms after being tiled. It could go to zero by not rotating
  pixels at all and writing an EXIF orientation tag instead — but that changes
  what the file *is*, trading the time for a dependence on every viewer honouring
  the tag, which is precisely what this app rotates in order not to need.
- **The JPEG encode**, around 240 ms and now comfortably the largest single item
  in a capture. It is not wasteful — 12.5 MP at roughly 52 MP/s through
  libjpeg-turbo is about what that costs — so the options are quality, a
  different encoder, or leaving it alone.
