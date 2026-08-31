# Handover

A short brief for picking this up in a fresh session. Delete it once it has
served its purpose; [SESSION-LOG.md](SESSION-LOG.md) is the real record and
holds the reasoning behind everything below.

## Where things stand

**The target is one phone: a Pixel 9 Pro XL.** No Play Store, no other devices,
no release paperwork — deferred by the owner's decision, and nothing in the
current work depends on any of it.

Everything builds. **359 unit tests pass**, and **116 device tests** pass on the
phone. The build on the phone is current HEAD, md5 verified.

The app is usable. A shutter press takes a zero-shutter-lag merged raw capture
in about a second and writes a DNG and a JPEG to the gallery, and the status
line says what the merge bought: `8 frames · 91% kept`.

Recent work, newest first:

- **there is now a harness whose A/A passes.** Both implementations of a pass
  live in one binary and are timed back to back in one process, forty rounds,
  order alternated. Pairing the runs sees through a phone that wanders by a
  factor of three; pooled ranges do not — see `ShadingSpeedDeviceTest`
- **the shading pass is about 1.53x faster**, measured that way — 39 of 40
  rounds in one run and 38 of 40 in a second. The row's grid position, the
  column's, and the corner gains are all lifted out of the pixel loop
- **it now opens in a state worth showing.** ZSL defaults on, the PRO panel has
  a reset, the control row is six settings rather than nine because a phone
  shows six, and the timer and guides are remembered. The self-timer can be
  called off mid-countdown
- **the merge readout** — the frames that went in and the share that survived
  rejection, which the app had always measured and only ever logged
- **a control now has a shape that says what touching it will do**; actions
  live beside the shutter and are squared off, settings are pills
- **on the pipeline**: the tone stage is about half of `demosaic+tone`, the
  largest item in the develop. Which part of it is unknown — see below

`test-photos`: instrumentation runs write real captures into
`/sdcard/DCIM/Multiframe/`, and roughly 80 have accumulated. They are the
owner's to delete.

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
- **Run an A/A before believing an A/B, and alternate the order.** The develop
  harness, installed twice under two names and compared against itself, gives
  per-round medians of 188, 193, 232, 486 against 201, 191, 478, 222. It
  manufactures two and a half times out of nothing. A fixed install order
  produced a clean-looking 79 ms result that evaporated when the change was
  built and measured directly. Trust *separation* — forty samples of each with
  no overlap — and never a difference of medians.
- **Across installs, trust separation. Within one process, pair the runs.** Two
  rules for two instruments, and the second is much the stronger.
  `ShadingSpeedDeviceTest` holds both implementations in the binary: its pooled
  ranges overlap almost entirely, because the phone wanders by a factor of three
  over the eight seconds it takes, and the paired comparison still calls the
  same data 39 rounds out of 40. The A/A then checks the *pairing* rather than
  the ranges — 19 of 40 is a fair instrument, two rounds in three would be a
  thumb on the scale.
- **`-ffast-math` means the same expression written twice is not the same
  arithmetic.** A rearrangement of the shading loop that preserved every
  operation, order and association still differed from the original over a fifth
  of the plane, by one unit in the last place, because the compiler fused and
  reassociated the two loops differently. Assert a stated bound rather than
  equality, and say what the bound rules out.
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

**Two things need the owner and a real scene, and they outrank all the code.**

- **Nobody has swept a real scene with the mosaic.** Every stitching claim rests
  on synthetic frames cut from a generated image. That is the right way to test
  the algorithm and it is not evidence about a building. Until it has been
  pointed at one, super-res should not be the thing anyone is shown first.
- **Nobody has looked hard at a batch of real photographs.** The parity tests
  prove the native path matches the Kotlin one; neither proves the picture is
  good. Twenty frames in mixed light, looked at properly, would tell more than
  any test here.

**On the pipeline**, `demosaic+tone` is now most of the develop and the
instrument to attack it with exists. A capture on a warm phone this session:

    black 18-35ms, hotpixels 20-50ms, shading 22-34ms, demosaic+tone 168-250ms

The tone half of that last figure is about as expensive as the demosaic half —
established, forty samples each, balanced ordering, no overlap. *Which part* of
the tone stage is not established, and the obvious suspect is innocent:
replacing `shoulderCurve`'s `std::exp` with an inline series changed nothing.

**The blocker on that has been cleared.** `nShadingBench` is the pattern: hold
both implementations in one binary, alternate the order, pair the two runs
within a round, and require the A/A to come back near 20 of 40 before believing
anything the A/B says. It works because the phone's wander is shared by two runs
a few milliseconds apart — which is exactly what a reinstall between runs
destroys. The same shape applied to the tone stage would make the colour matrix,
the rendering curve's maxima and branches, and the display lookup separable at
last.

Two things to know before writing that harness. The rearrangement it measures
will **not** be bit-exact under `-ffast-math`, so assert a bound; and it needs
its own reference implementation kept in the binary, which is what
`applyShadingReference` is for. Both cost about ten lines and both were learned
the expensive way.

Do not start by assuming the demosaic is the cost. Four assumptions about where
develop time goes have been wrong here: the JPEG encode, the sharpening, the
exponential, and that a rearrangement would come back identical.

## How to measure

In decreasing order of authority:

- **The real capture path is the only authority on what a photograph costs.**
  Run `ZslStreamDeviceTest#repeatedCapturesTakeAConsistentTime` and read
  `develop breakdown`, `develop stages`, `develop:` and `sharpen:` out of
  logcat. That is real camera frames.
- **`ShadingSpeedDeviceTest` is the only instrument here that can compare two
  implementations**, because it is the only one that does not need a second
  install. Both live in the binary, forty rounds, order alternated within the
  round, and the comparison is paired rather than pooled. Its A/A test is not a
  formality and runs first. Copy this rather than the two below.
- **`SharpenSpeedDeviceTest` and `DevelopSpeedDeviceTest` isolate a stage**, and
  are comparison instruments only. The develop one **reads about twice what a
  capture pays and nobody knows why** — clock ramp, exposure and foreground
  scheduling were each tried as explanations and each failed. Its
  much-advertised 2% repeatability (406, 414, 415 across three runs) was
  measured across runs that *shared one install*, and an A/B cannot: reinstall
  between runs and it swings two and a half times. Never quote it as what a
  capture costs, and never trust it without an A/A.
- **The emulator is not slow, it is fast**, which is worse. An arm64 image on
  Apple silicon runs sharpening in 6 ms where the phone takes 37-100, and the
  JPEG strips in 8 ms against 48-107. A regression that doubled a phone's cost
  would look healthy there. The four timing harnesses now say so in the log line
  beside the figure.
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
