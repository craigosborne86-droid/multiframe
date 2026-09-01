# Handover

A short brief for picking this up in a fresh session. Delete it once it has
served its purpose; [SESSION-LOG.md](SESSION-LOG.md) is the real record and
holds the reasoning behind everything below.

## Start here: two things need the owner, and they outrank the code

The last agreed task — folding the develop's three preparatory passes — is
**done and measured**; see *One sweep, and what it left behind* below. Nothing in
the code is queued behind it, and the two items that have been waiting longest
need a person and a scene rather than a session:

- **Nobody has swept a real scene with the mosaic.** Every stitching claim rests
  on synthetic frames cut from a generated image. That is the right way to test
  the algorithm and it is not evidence about a building. Until it has been
  pointed at one, super-res should not be the thing anyone is shown first.
- **Nobody has looked hard at a batch of real photographs.** The parity tests
  prove the native path matches the Kotlin one; neither proves the picture is
  good. Twenty frames in mixed light, looked at properly, would tell more than
  any test here.

If the next session is a code session anyway, the one unpriced question with a
strong prior is in *The next fold, which is not priced* below. Price it before
building it — that is what made the last one worth doing.

Before measuring anything, confirm a green baseline on a **rested, charged**
phone:

```bash
adb shell am instrument -w dev.multiframe.camera.test/androidx.test.runner.AndroidJUnitRunner
```

124 tests should pass. If `repeatedCapturesTakeAConsistentTime` fails, check
`/proc/meminfo` and the battery before suspecting the code — see the measurement
rules.

## Where things stand

**The target is one phone: a Pixel 9 Pro XL** — and a Pixel 11 Pro is on order,
512 GB / 16 GB, which changes more than it looks like. See *When the phone
changes* at the end. No Play Store, no other devices, no release paperwork —
deferred by the owner's decision, and nothing in the current work depends on any
of it.

Everything builds. **359 unit tests pass**, and **124 device tests** pass on the
phone in a clean full suite. The capture consistency check fails once the phone
is short of memory, which is device state and not a regression — see the
measurement rules below. The build on the phone is current HEAD.

The app is usable. A shutter press takes a zero-shutter-lag merged raw capture
in about a second and writes a DNG and a JPEG to the gallery, and the status
line says what the merge bought: `8 frames · 91% kept`.

Recent work, newest first:

- **the develop's three preparatory passes are one pass.** `black`, `hotpixels`
  and `shading` fold into a single sweep that reads the merged `uint16` and
  writes the plane once: 1.36-1.45x of the three, 155 of 160 rounds. Hot pixel
  detection moved into the raw domain, which is exact once the clamp at the
  black level is carried as `max(code, black)` — it is not exact without it
- **a race in `suppressHotPixels` is fixed**, which had made the develop not
  quite a function of its input. Replacements are collected and applied once the
  bands have finished, so no decision reads a value another band is writing —
  and a corrected site is no longer its neighbour's reference, which is what the
  Kotlin the develop falls back to has always done. The parity fixture that can
  see that difference was checked against the old pass: it fails by 45 codes
- **the demosaic is vectorised**, eight pixels at a time out of one set of
  deinterleaving loads: 1.39x of the whole pass, 78 of 80 rounds, and 1.52x
  against the per-pixel form of two sessions ago. The colour matrix went into
  the same vectors once the harness could see it
- **the demosaic's loop was split by parity** first, worth about 4% — 105 of 176
  rounds. Worth knowing it is *only* 4%: the cost was never the branching
- **the highlight roll-off is a table now**, worth 1.13-1.18x of `demosaic+tone`
  on a bright frame and nothing on a dark one, at one display code on five bytes
  in a million. It is **the first approximation in the render** — everything
  else here is exact or pinned to a reference — so the bound is asserted at
  capture size rather than argued
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
  parity test renders each width a second time with sharpening off and asserts
  the two differ, so it cannot pass by sharpening nothing. The newest of them,
  `aDefectDoesNotBecomeItsNeighboursReference`, was built and then run against
  the pass it exists to catch: 45 display codes apart there, equal here. Do that
  rather than reason about whether a fixture is live — most arrangements of two
  defects cannot tell those two implementations apart at all, and it takes an
  argument to see why.
- **No timing is ever taken from the emulator.** It settles framework behaviour,
  never performance.
- **Third-party code is a last resort, and there is exactly one piece of it.**
  libjpeg-turbo is vendored under `cpp/third_party/`, pinned to the framework
  encoder's output rather than to a Kotlin reference, because it cannot be.
- **Negative results are recorded and reverted**, not kept on faith. Twelve so
  far:
  a lower-priority DNG writer thread, disabling filtering on the rotation,
  aligning natively inside the merge benchmark, interleaving the merge's two
  accumulation planes, the inline exponential (which later turned out to be a
  false negative from the broken harness), `__restrict` on the develop's input
  and output, four separate attacks on the desaturation in one sitting, and two at the display
  chain. Where an experiment leaves a *probe* worth keeping, the probe stays and
  the change goes — `kToneNoDisplayChain` is there for that reason.

A recurring lesson, hit five times now: a single reported figure often bundles
two very different things. Splitting the timer before optimising found that
alignment was 83% of the "merge", that the JPEG encode — not the demosaic — was
the largest item inside "develop", and this session that sharpening was a third
of what was left.

## What the develop is made of

A capture is now two passes: the prepass and `demosaic+tone`. On a **rested**
phone at 36 C, settled shots, before the fold:

    black 18-21ms, hotpixels 15-20ms, shading 18-20ms, demosaic+tone 99ms

At the end of a measuring session, 48% and 38 C, with the fold in:

    prepass 57-87ms, demosaic+tone 81-114ms

**Those two lines are not comparable and subtracting them is the mistake this
log has made before.** The same three passes read 76-85 ms in `nPrepassBench`
last session and 94-117 ms in it today; the phone, not the code, is most of the
difference. What transfers is the paired ratio: the fold is 1.36-1.45x of the
three, 155 of 160 rounds, measured inside one binary in one process.

The same phone four hours into a session of measuring reads roughly twice
everything. Do not mix the two, and do not compare either with the 38 ms shading
or the 146 ms `demosaic+tone` in older entries.

`ToneAblationDeviceTest` takes that last figure apart by running the whole pass
with one item removed. As paired ratios, which are what transfer, after the
roll-off was tabulated:

    renderLinear                      1.82x   16 of 16
      its highlight roll-off          1.25x   15 of 16
      its highlight desaturation      1.24x   15 of 16
    everything after the demosaic     2.36x   16 of 16
    the whole display chain           1.06 - 1.32x   59 of 64
    the colour matrix, the display *table*     below the floor

**The rendering curve is the largest item again.** The desaturation came up
level with the roll-off and looked like the same job — a block behind a
data-dependent branch with a divide in it. **It is not, and four experiments say
so:** its second three-way maximum is free to remove and buys nothing (55 of 96
rounds), its divide is not the cost (28 of 48), keeping the tone parameters in a
local against the `uint8_t*` aliasing barrier is marginal (30 of 48, p = 0.06),
and making its branch arithmetic is **1.13x worse** (7 of 48).

Removing the whole block saves a fifth of the pass; removing any part of it saves
nothing. **Do not build a table for the divide** — that was the roll-off's story
and the probe that justified it there comes back a coin flip here.

**The display chain is the other fifth, and it is not the lookup.** The table
load is free — 241 rounds of 480 over thirty runs — but that comparison only ever
swapped a load for an fma, and the clamp, scale and convert were on both sides of
it. `kToneNoDisplayChain` is the probe that can see them, and it puts the chain
at 1.06-1.32x. Vectorising those three operations bought nothing, through the
stack or through lane extracts, 69 rounds of 128.

**The roll-off is the third fifth, and it does split** — unlike the other two.
`kToneFlatShoulder` keeps its branch and its three multiplies and drops the
lookup: the lookup is 1.08-1.27x, 51 of 64 rounds, and is the larger of the two
halves. But
every cheaper shape for the lookup is closed by a number this log already has:
the one dead operation in it is worth 1.008x at best against a floor of
1.05-1.10x; storing the slope beside the value saves a single subtract (the two
entries are already one cache line) and doubles the table to the 16 KB that cost
dark frames 5-13%; nearest-entry needs 13,366 entries, 52 KB; and folding the
scale into the display's ×4095 saves three multiplies but buys them with an
out-of-bounds read of a 4 KB table on the strength of an analysis.

**All three failures have the same cause and it is worth stating once.** The
per-pixel tail is latency-bound, not throughput-bound: the arithmetic already
sits in the slack left by the dependency on `renderLinear`'s output and by the
stores, so making it cheaper or wider compresses something that is not the
critical path. The roll-off, the desaturation and the display chain each cost
about a fifth of the pass and none of them has a part that can be made cheaper.
Everything that has worked here was in the demosaic; nothing tried in the tail
has. **If you want this pass faster, the honest next move is not another
peephole — it is fewer passes.**

**Read those as shares, not as costs.** The colour matrix was under the floor,
then 1.09-1.25x with 57 of 64 rounds, then under the floor again, without the
code between those readings being touched: vectorising the demosaic around it
made it a larger part of a smaller total, and vectorising it in turn put it back.
An item's share is not a property of the item.

**The roll-off's price belongs to the photograph, not the code.** Its work sits
behind `if (scenePeak > knee)`: at 82% of the frame above the knee `renderLinear`
is 1.58x and 2.20x on two runs, both 16 of 16; at 1% it does not separate at all,
8 and 9 rounds of 16. Never quote a figure for this pass without the scene beside
it.

**The harness resolves about a tenth of the pass and no better.** Sixteen rounds
put the colour matrix at 1.11x, 1.05x, 1.13x and 1.04x with win counts of 16, 11,
9 and 8 — one real reading and three coin flips. Below that floor, raise the
rounds or shrink the enclosing pass; do not read the ratio.

### One sweep, and what it left behind

`black`, `hotpixels` and `shading` were three sweeps of a 50 MB plane and are now
`applyPrepass`, which reads the merged `uint16`, corrects defective sites on the
way past, and writes the plane once:

    three passes   median 102, 117, 94, 91 ms
    one pass       median  75,  81, 69, 66 ms
    the fold       1.36 - 1.45x     155 of 160 rounds

Four things are worth knowing before touching it.

- **Detection runs on the raw codes, and the clamp is what makes that exact.**
  ±2 preserves CFA parity, so all five sites share a black level, and the
  develop's comparison survives the change of domain — *provided* the clamp
  underneath the black level is carried as `max(code, black)` on all five. It is
  monotone but not affine, and the naive `v > n + t*r` fires where the develop
  cannot: wherever all four neighbours read below the black level, anything
  within ~96 codes above the largest of them gets zeroed. Deep shadow, a tenth of
  full scale a site. Two integer maxima remove the caveat entirely.
- **Only the winner is converted.** `max((x-black)/range, 0)` is monotone, so the
  largest of the four normalised neighbours is the normalised largest. Take the
  max and min of the four as integers; convert two values, not five.
- **There is no halo and no lag.** Nothing is written before it is read, which is
  what killed the race rather than working around it: `nPrepassBench`'s A/A gets
  the same plane bit for bit, twice, across whatever bands the threads claim.
- **The two planes differ, and the bound is the point.** A quarter of the plane
  comes back one unit in the last place away (worst 9.53e-07), for the reason the
  shading rearrangement recorded: `-ffast-math` fuses and reassociates the same
  expression differently in different surroundings. A *decision* that differed
  would show as a tenth of full scale, four orders up. That is what the bound
  rules out and what the test says it rules out.

**It is less than the traffic model predicts, and that is the finding.** Three
passes move about 225 MB and one moves 75 MB; folding one was worth 1.25-1.29x,
which extrapolates to 1.74x for folding all three. Neither model survives: the
fused sweep is a fatter sweep, reading five `uint16` per interior pixel and
converting three values where the black pass converted one. **Taking the traffic
away exposes the arithmetic that was hiding behind it.** Do not price the next
fusion by counting bytes alone.

### The next fold, which is not priced

The develop is now two passes, and the same question applies to the pair that is
left. `applyPrepass` writes a 50 MB plane and `demosaicAndTone` reads it back;
the demosaic reads thirteen neighbours spanning ±2 rows, which is the same
stencil shape the hot pixel pass had and the reason the plane exists at all — the
comment in `nDevelop` says preparing inline would repeat the work thirteen times.

A sliding window of about five prepared rows — 80 KB at this width, which is L2 —
answers that objection without repeating anything, and would take 100 MB of DRAM
traffic out of the develop. It is the same trick that just worked, one level up.

**It is a guess until it is priced, and the last one overshot by a factor of
two.** `nPrepassBench` is the pattern to copy: build the wrong-but-cheap version
first — a demosaic reading from a five-row cache that has been filled by the
existing prepass, which renders the wrong picture at band edges — time it against
the two passes as they ship, and only then decide whether the real thing is worth
writing. Note that the demosaic is hand-vectorised and reads a *contiguous* row;
a circular row cache must keep that property or it will measure its own indexing.

### That move is done, and here is what it cost

`ShoulderLut` is built per capture beside `DisplayLut` and the branch that
guards it stays. Two things to know before touching it:

- **It is interpolated, and has to be.** `DisplayLut` bins the *linear* value
  into 4096, so a scale that is out by a few ten-thousandths moves a bright
  pixel across a bin. Truncating a table on a curve is first order in the cell,
  about 6e-4 here — twice a bin. Nearest-entry put 892,955 bytes of a render one
  code out and broke the parity test at three codes. Interpolated it is 265.
- **The branch is not redundant.** Reading the table unconditionally — which is
  correct, since it answers 1 below the knee — cost a dark frame 0.89x, 14 of 16
  rounds. What is expensive is the body, not the test.

`ToneAblationDeviceTest` asserts both: the render against the arithmetic at
capture size, and that the table is never slower at either end of the range of
scenes.

Do not start by assuming anything here. Seven predictions about this pass have
been wrong: the JPEG encode, the sharpening, the exponential, that a
rearrangement would come back identical, that the demosaic was the expensive
half of the pass named after it, that a table would not need interpolating, and
that the branch guarding the roll-off was its cost. The last two were written
into the code as comments and refuted within the hour by the harness.

**The demosaic is vectorised and is no longer where the time is.** Three shapes
of it are in the binary and all three can be timed against each other:
`kToneFull` (NEON octets), `kToneScalarDemosaic` (the parity-split scalar loop),
and `kToneUnsplitDemosaic` (the original per-pixel dispatch).

If you extend it, the shape to keep is that one `vld2q_f32` pass over five rows
supplies *both* halves of the octet — the evens and odds of eight are exactly a
Bayer row's two sites, which is why the octet and not the quartet is the unit.
And `__restrict` on the input and output, the textbook fix for a `uint8_t*`
output that may alias anything, was measured at **21 of 48 rounds, nothing**; it
did not stop the hand vectorisation working and it is not the lever it looks
like.

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
- **Set a threshold from the distribution, not from a round number.** This has
  now been got wrong twice, the second time after writing the lesson down. A
  30-70% band on a paired A/A sounds strict and is not: at twenty rounds a fair
  coin falls outside it 4.1% of the time. Worse, a one-sided "must not be slower"
  written as `wins <= ROUNDS / 2` fails **40%** of the time when nothing is
  wrong, because half the rounds is exactly where a true null sits. Buy the
  tightness with rounds — at forty, 30-70% costs 0.6% — and put the one-sided
  bound at three quarters, which costs 1.1% and still catches the regression it
  is there for. A test that cries wolf gets written down as flakiness, which is
  what happened to `repeatedCapturesTakeAConsistentTime` for four sessions.
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
- **The emulator settles correctness when the phone is unreachable.** When
  wireless adb dropped the phone entirely this session, the AVD on
  `/Volumes/MultiframeAVD` booted and ran the develop parity tests at 0/255,
  including the widths that strand the vector loop. It says nothing about speed
  and the rule below stands. Note it needs the app launched once by hand
  (`adb shell monkey -p dev.multiframe.camera -c android.intent.category.LAUNCHER 1`)
  before instrumentation will start; the first attempts die as `failed to
  complete startup`, which reads like a crash and is not one.
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
- **Memory pressure is a separate hazard from heat, and looks nothing like it.**
  At 30 C with 850 MB free of 15.5 GB and 5.8 GB of swap in use, rounds that
  take 110-160 ms come back at 400-780 ms, about one in three, at random. The
  paired A/A stays honest through it — 20 of 40, ratio 1.00 — so nothing is
  *biased*, but the power to see a few per cent is gone and a comparison needs
  ten runs instead of one. Read `/proc/meminfo`, not only `dumpsys battery`.
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

## When the phone changes

A Pixel 11 Pro is on order — 512 GB, so 16 GB of RAM, which is what this phone
has and means the ring depth and the memory budget behave as they do today.
Tensor G6, Android 17 (which komodo already runs, so `targetSdk` is untouched),
a new 50 MP 1/1.3" main sensor, and a 6.3" screen where this one is 6.8".

**Correctness should carry over untouched, and not by luck.** Every sensor
property is read from the camera rather than assumed: `SensorProfile.from` takes
the CFA arrangement, the black level pattern and the white level from
`CameraCharacteristics`, `ColorProfile.from` takes the matrix and neutral point
from the capture result, the shading map arrives per capture, and
`ZslPolicy.evaluate` picks the largest zero-stall raw configuration the camera
offers rather than naming a size. The Pixel 9 numbers in `SensorProfile.DEFAULT`
are a fallback and nothing else. Every parity test is synthetic, so all of them
should pass on the first run.

**Every timing in this file and in the log is komodo's, and none of it
transfers.** Not the milliseconds, and *not the ratios either*, which is the
part that is easy to get wrong. The G6 is reported to pair with LPDDR5X-8533
against the G4's LPDDR5X-4200 — roughly twice the memory bandwidth, against a
much smaller gain in the cores. Read that against what this project has just
measured:

- **The prepass fold wins by removing traffic**, 225 MB down to 75 MB, and the
  entry above records that it already beats neither the traffic model nor the
  extrapolation — the arithmetic underneath is showing. Double the bandwidth and
  1.36-1.45x shrinks. It cannot reverse, since one sweep cannot cost more than
  three, but the prize is a property of the machine.
- **The next fold is worth less than it looks, there.** It is justified by taking
  another 100 MB of traffic out. Price it on the phone it will run on.
- **The twelve negative results are device-scoped too.** The per-pixel tail was
  found latency-bound on a G4. Wider cores could make one or two of those
  peepholes live again — which is a reason to re-run the ablations before
  believing the conclusion, not a reason to assume they changed.

**The old phone's baseline is taken and committed: [BASELINE.md](BASELINE.md).**
Correctness, the capture path, every paired ratio and every isolate, with the
battery, temperature and free memory stamped beside them, because a figure
without its device state is not a figure. Compare the new phone against that file
rather than against anything quoted in the log, and compare *ratios* — the rule
that has cost this project the most time is setting a figure from one run beside
a figure from another, and a change of phone is that mistake with a hardware step
in the middle. Keep komodo long enough to settle anything the new readings make
surprising.

**First day, in this order:**

1. `adb shell am instrument -w dev.multiframe.camera.test/androidx.test.runner.AndroidJUnitRunner`
   — 124 tests. Correctness first, before anything is measured.
2. Take a shot and read the `ZSL` decision out of logcat. **This is the one real
   functional risk:** if the new sensor or ISP has no zero-stall `RAW_SENSOR`
   configuration, `ZslDecision.Fallback` says so in words and the app drops to
   sequential capture. It degrades rather than breaks, but zero shutter lag is
   the feature.
3. Read `develop:` out of the same capture and write down what a photograph
   costs on this phone. That is the new baseline; the old one is history.
4. Re-run `ShadingSpeedDeviceTest` and `ToneAblationDeviceTest`. Their A/As come
   first, as always, and on an unfamiliar machine they matter more rather than
   less.
5. Look at the control row. **Six, because a phone shows six** was judged by eye
   on a 6.8" screen; this one is 6.3" and 1280 px across. The number may still
   be six. It should be looked at rather than inherited.

## What needs you rather than me

The two at the top of this file, and:

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
