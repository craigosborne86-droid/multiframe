# Handover

A short brief for picking this up in a fresh session. Delete it once it has
served its purpose; [SESSION-LOG.md](SESSION-LOG.md) is the real record and
holds the reasoning behind everything below.

## Where things stand

**The target is one phone: a Pixel 9 Pro XL.** No Play Store, no other devices,
no release paperwork — deferred by the owner's decision, and nothing in the
current work depends on any of it.

Everything builds. **359 unit tests pass**, and **119 device tests** pass on the
phone — a clean full suite, which this log had not previously recorded: the
consistency check that failed four runs in a row turned out not to be flaky at
all. The build on the phone is current HEAD, md5 verified.

The app is usable. A shutter press takes a zero-shutter-lag merged raw capture
in about a second and writes a DNG and a JPEG to the gallery, and the status
line says what the merge bought: `8 frames · 91% kept`.

Recent work, newest first:

- **`repeatedCapturesTakeAConsistentTime` failed four times and none of them
  were thermal.** It was the MediaStore publish, which quadruples across four
  shots on a 90%-full volume while the pipeline settles. The test drops a
  warm-up shot and asserts on the develop less the publish now, and passes
  standing alone and in a full suite — which is where all four failures were
- **a threshold that was a round number rather than a distribution.** The tone
  A/A's 30-70% band is 6 to 14 at twenty rounds, which a fair coin fails 4.1% of
  the time, and did. Forty rounds, same band, 0.6%

- **`demosaic+tone` has been split, and it is the rendering curve.** Not the
  demosaic, not the colour matrix, not the display lookup. `renderLinear` is
  1.6-2.0x of the whole pass and its highlight roll-off is most of that, 16 of
  16 rounds in every run. `ToneAblationDeviceTest`
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

**On the pipeline**, `demosaic+tone` is the develop and has now been split. A
capture on a **rested** phone at 36 C, settled shots:

    black 18-21ms, hotpixels 15-20ms, shading 18-20ms, demosaic+tone 99ms

The same phone four hours into a session of measuring reads roughly twice all of
those. Do not mix the two, and do not compare either with the 38 ms shading or
the 146 ms `demosaic+tone` in older entries.

`ToneAblationDeviceTest` takes that last figure apart by running the whole pass
with one item removed. As paired ratios, which are what transfer:

    everything after the demosaic     2.2 - 3.0x     16 of 16, every run
    renderLinear                      1.9 - 2.5x     16 of 16, every run
      its highlight roll-off          1.44 - 1.6x    16 of 16, every run
      the exponential inside it       1.15 - 1.21x   52 of 64 rounds pooled
    the colour matrix, the display table, the desaturation   below the floor

**The rendering curve is the cost.** The demosaic is under half the pass, and
the three items anyone would name first are all smaller than the harness
resolves in sixteen rounds.

**The roll-off's price belongs to the photograph, not the code.** Its work sits
behind `if (scenePeak > knee)`: at 82% of the frame above the knee `renderLinear`
is 1.58x and 2.20x on two runs, both 16 of 16; at 1% it does not separate at all,
8 and 9 rounds of 16. Never quote a figure for this pass without the scene beside
it.

**The harness resolves about a tenth of the pass and no better.** Sixteen rounds
put the colour matrix at 1.11x, 1.05x, 1.13x and 1.04x with win counts of 16, 11,
9 and 8 — one real reading and three coin flips. Below that floor, raise the
rounds or shrink the enclosing pass; do not read the ratio.

### The move that is now identified and costed

Replace the roll-off's `std::exp`, or better, replace
`shoulderCurve(p, knee) / p` outright with a table on `p` built per capture the
way `buildDisplayLut` already builds one — that kills the call and the divide
together. The ceiling is the 1.44-1.6x the whole roll-off is worth; the
exponential alone is 1.15-1.21x of it.

**It would be the first approximation in the render, and that is the decision to
put to the owner rather than slip in.** Everything in this pipeline so far is
either exact or pinned to a reference; `DisplayLut` makes a point of being exact
and explains why that is the only reason it was worth doing. A table on a
continuous input cannot be. A 4096-entry interpolated table is accurate to about
1e-6, four orders below a display code, so the picture would not change — but
the *kind* of claim this pipeline makes would.

Note also that this log's earlier "replacing the exponential changed nothing"
was a false negative from the broken harness, so do not take it as evidence.

Do not start by assuming the demosaic is the cost. Five assumptions about where
develop time goes have been wrong here: the JPEG encode, the sharpening, the
exponential, that a rearrangement would come back identical, and that the
demosaic was the expensive half of the pass named after it.

## How to measure

In decreasing order of authority:

- **The real capture path is the only authority on what a photograph costs.**
  Run `ZslStreamDeviceTest#repeatedCapturesTakeAConsistentTime` and read
  `develop breakdown`, `develop stages`, `develop:` and `sharpen:` out of
  logcat. That is real camera frames.
- **`ShadingSpeedDeviceTest` and `ToneAblationDeviceTest` are the only
  instruments here that can compare two implementations**, because they are the
  only ones that do not need a second install. Both candidates live in the
  binary, the order alternates within a round, and the comparison is paired
  rather than pooled. Their A/A tests are not a formality and run first. Copy
  these rather than the two below.
- **Set a threshold from the distribution, not from a round number.** A 30-70%
  band on a paired A/A sounds strict and is not: at twenty rounds a fair coin
  falls outside it 4.1% of the time. Buy the tightness with rounds — at forty
  the same band costs 0.6%. A test that fails one run in twenty-five gets
  written down as flakiness, which is what happened to
  `repeatedCapturesTakeAConsistentTime` for four sessions.
- **Report the paired ratio, not a difference of medians.** The same comparison
  read 104 ms of 320 and, twenty seconds later, 269 ms of 419: both true,
  neither transferable, because the phone had slowed by half in between. The
  within-round ratio is untouched by that. The win count is the significance;
  the ratio is the size.
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
- **`MergeSpeedDeviceTest` reports what a capture pays**, which it was believed
  for three sessions not to. It aligns natively now, as a capture does — but
  that was not why it was thought to read high, and it never did: measured
  against a capture on the same phone minutes apart, 39 ms an accumulated frame
  against 37. The old note compared its figure to a capture figure from another
  day, which is the one comparison the rules above forbid. It also runs both
  gaps and finds the accumulation cannot tell them apart, 7 of 16 pairs.

Hard-won notes that still apply:

- **Alternate the two builds several times, not once.** Push both APKs to
  `/data/local/tmp` and `adb shell pm install -r` from there: about twenty
  seconds against three minutes for a wireless install, which is what makes four
  alternations affordable. To build a before-APK that shares a new harness,
  splice the old implementation back into the current tree, build, save the APK,
  then restore — that is how this session's sharpening comparison was made.
- **The device degrades over an afternoon, and a short rest does not fix it.**
  Watch `dumpsys battery` and `/proc/meminfo`, and know that both can look fine
  while everything runs at half speed. Over one session the same pass went 172
  to 313 ms with per-round times spreading from 223 ms to 973 ms, every develop
  stage roughly doubled, a nine-minute rest made it worse, and the battery fell
  43% to 23%. Plug it in and leave it before a measuring session.
- **Never subtract a figure in the log from one taken in a different run.** Only
  an alternated comparison inside a single session means anything. This is the
  easiest rule here to keep while breaking: `MergeSpeedDeviceTest` was believed
  for three sessions to read high, on the strength of its figure being set
  beside a capture figure from another day. It does not. The rule has to be
  applied to the *notes* as well as to the measurements.
- **Wireless adb drops writes** under this load: installs fail with "device
  offline" and then succeed on a retry. Loop the install two or three times.

## Environment

- **`adb logcat -d` after a long test loses the start of it.** These harnesses
  print a line per comparison over a minute or two and the ring buffer rolls, so
  the early lines are gone by the time the run ends. Capture continuously
  instead: `adb logcat -c`, then `adb logcat -s <Tag> > file &` before the run,
  and read the file after. Half an hour went on re-running tests whose output
  had already scrolled away.
- **Wireless adb was fine this session** — 80 MB/s pushes and installs in about
  a second, over mDNS. An earlier session recorded it degraded to three minutes
  an install; that was the link on the day, not the transport. If it is slow,
  push both APKs to `/data/local/tmp` and `adb shell pm install -r` from there,
  and drive tests with
  `adb shell am instrument -w -e class <Class> dev.multiframe.camera.test/androidx.test.runner.AndroidJUnitRunner`
  rather than through Gradle, which looks hung while it waits.
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
