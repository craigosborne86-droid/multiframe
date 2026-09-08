# What komodo cost

The Pixel 9 Pro XL's last full reading, taken deliberately before the phone
changes. A Pixel 11 Pro replaces it, and **every figure in
[SESSION-LOG.md](SESSION-LOG.md) was measured here** — so this file exists to be
the thing the new phone is compared against, and to say plainly which parts of
it are allowed to be compared at all.

## Two runs, and why both are here

| | run A | run B |
|---|---|---|
| Commit | `d40f7fd` | `9f6d0c3` |
| Date | 1 September 2026 | 2 September 2026 |
| Battery | 45% falling, unplugged | 27% rising, on AC |
| Temperature | 33.1 → 36.9 C | 37.2 → 38.4 C |
| Free memory | 800 → 543 MB | 1158 → 1422 MB |
| Device tests | 123 of 124 | **128 of 128** |

Run A was rested but memory-pressed, and one test failed on exactly that. Run B
followed a battery-flat shutdown, so its memory was fresh and its silicon was
warm and charging.

**Neither is "the" reading, and the gap between them is the most useful thing in
this file.** Run B is about twice as slow as run A in absolute milliseconds while
agreeing with it on every ratio. A phone that has just been plugged in at 27% and
sits at 38 C is not the same instrument as one resting at 33 C, and no amount of
care in the code shows through that. Compare the new phone against the **ratios**
below. The milliseconds are here to show their own spread.

    Device: Pixel 9 Pro XL (komodo)
    Build:  google/komodo/komodo:17/CP2A.260805.005/15828068:user/release-keys
    APKs:   md5-verified against the local build on both runs

## How to use this file

- **The paired ratios transfer. Nothing else does.** They are two
  implementations timed against each other inside one process, alternating
  within a round, so the phone's wander cancels — as the two runs below
  demonstrate rather than assert.
- **The milliseconds are shape, not target.** Which stage dominates, how wide
  the spread runs. The Tensor G6 is reported to pair with roughly twice the
  memory bandwidth against a much smaller gain in the cores, which is enough on
  its own to move some of the ratios. See *When the phone changes* in
  [HANDOVER.md](HANDOVER.md).
- **Never subtract a figure here from one taken in another run.** That is the
  rule this project has broken most often, and this file is dated and stamped so
  that it cannot be broken by accident.

## Correctness

**359 unit tests pass. 128 of 128 device tests pass in a clean full suite**
(run B), the capture consistency check included.

Every C++ stage is pinned to its Kotlin reference and all of those pass: develop
(worst 0/255), sharpening, shading, alignment, rotation, the merge, and the JPEG
encoder against the framework's.

## The capture path

What a photograph actually costs, from `ZslStreamDeviceTest`. The develop is two
passes as of `e97c89b`, where it was four; `prepass` is `black`, `hotpixels` and
`shading` folded into one sweep.

    run A   prepass 41-85ms    demosaic+tone 71-113ms    develop 112-199ms
    run B   prepass 74-121ms   demosaic+tone 77-157ms    develop 152-279ms

    luma + sharpen   run A 34-65ms    run B 29-95ms

Both are true. Neither is transferable. Run B is the warmer, charging phone.

## The paired instruments, which are the ones that transfer

                                run A                run B
    the shading hoist           1.52x  40 of 40      1.57x  36 of 40
    the prepass fold            1.42x  39 of 40      1.52x  37 of 40

Across all five runs this session the fold reads **1.36 - 1.52x, 192 of 200
rounds**, with A/As at 1.01x, 0.98x, 1.03x, 1.01x and 1.00x.

`ToneAblationDeviceTest`, sixteen rounds each, run A, as paired ratios — a figure
above 1 means the item costs that much of the pass:

    everything after the demosaic          2.25x   16 of 16
    renderLinear                           1.81x   16 of 16
      its highlight desaturation           1.15x   14 of 16
      its highlight roll-off               1.12x   13 of 16
        the roll-off's lookup alone        1.10x   12 of 16
    the whole display chain                1.13x   11 of 16
    the colour matrix                      1.05x   11 of 16
    the display table                      1.02x    9 of 16   (below the floor)

And the three that are negative on purpose — the shipping shape winning against
what it replaced:

    the computed roll-off, against the table       0.71x    1 of 16
    the scalar demosaic, against the vector one    0.67x    0 of 16
    the per-pixel demosaic, against both           0.61x    0 of 16

**The roll-off's price belongs to the photograph.** At 82% of the frame above the
knee `renderLinear` is 1.87x, 16 of 16, and its table is worth 1.20x; at 1% it is
1.20x, 14 of 16, and the table is worth 0.92x, 6 of 16. Never quote this pass
without the scene beside it.

**Every A/A, run B:** shading 20 of 40 at 1.02x, prepass 20 of 40 at 1.00x, tone
23 of 40 at 1.06x, GPU 20 of 40. In run A the shading A/A read 13 of 40, which
cleared its threshold of 12 and no more — a memory-pressed phone, and the reason
run B was worth taking.

## The isolates, which are the weakest instrument here

Single implementations, no second slot, and they cannot compare anything. The
develop one is known to read about twice what a capture pays and nobody has ever
explained why. Run A:

    develop at 4080x3072    median 225ms, 200-267ms over 9 rounds
    sharpen at 4080x3072    median  37ms,  31-43ms over 9 rounds
    merge, per frame        median 199-207ms, 176-234ms
    JPEG, strip encoder     134ms at 1912 KB, against the framework's 63ms at 2013 KB

## What it costs to reach the GPU

`GpuCrossingDeviceTest` moves 25 MB of merged CFA to the GPU, runs a shader
chosen to be too cheap to matter, and brings 50 MB of RGBA back. Forty rounds,
median round trip, zero wrong pixels on every route in both runs:

    route       run A    run B     what it is
    staging      37ms     77ms     host-visible staging, copied both ways
    shared       36ms     50ms     device-local and host-visible, uncached
    cached       10ms     20ms     host-cached, flush and invalidate by hand
    imported      7ms     16ms     an AHardwareBuffer imported into Vulkan

**The absolute figures moved by more than twice between the runs and the
ordering did not, nor did the ratio: importing is 4.01x of the uncached shared
route in run A and 3.77x in run B, 40 of 40 rounds both times.** That is this
file's rule demonstrated on its newest instrument.

**Against a develop of 112-199 ms (run A) or 152-279 ms (run B), the cheapest
crossing is 7 ms or 16 ms.** So the prior question is answered on this phone:
reaching the GPU is not what would make a GPU develop lose. Whether a GPU develop
would *win* is a different and much more expensive question, and nothing here
speaks to it — the dispatch is a floor that any real kernel is added to.

**The spread between routes is two findings, not one.** The first version of this
benchmark reported "importing is 4x cheaper than shared memory", which was true
and confounded: the shared route takes the first coherent memory the driver
offers, which here is *uncached*, while an imported buffer asks for
`CPU_READ_OFTEN` and gets *cached*. Splitting them, in run A:

    uncached coherent -> host cached      36ms -> 10ms
    host cached -> imported hardware      10ms ->  7ms

The cache policy is most of it. That matters, because the second step is the one
that needs the ring rebuilt around `AHardwareBuffer` and the first is a choice of
memory type.

**The download is the whole cost.** Upload is 2-10 ms for 25 MB on every route in
both runs; download is 4-77 ms for 50 MB. Reading GPU-written memory back is what
a GPU develop would have to be careful about, not getting the frame there.

**Setup is 9-25 ms**, which is a design constraint rather than a cost: an
instance, device, pipeline and pool built per capture would be a tenth of a
develop, so a GPU path must build them once and keep them.

## The failure in run A, and why it was device state

`repeatedCapturesTakeAConsistentTime` failed in run A:

    develop across four shots           521, 351, 2906, 354 ms
    of which MediaStore publish         136,  76,  309,  59 ms
    develop less publish, warm-up dropped        275, 2597, 295 ms

One shot in three at nine times its neighbours, which is the memory-pressure
signature the log describes and not the MediaStore publish that caused four
earlier failures — this figure already has the publish subtracted.

**Checked three ways, and all three agree.** Run standing alone twice
immediately afterwards it passed with spreads of 39 ms and 63 ms against the
suite's 2322 ms. The two neighbouring shots in the failing run were 275 and
295 ms, which is *fast* — a regression does not produce one outlier in three and
leave the rest quick. And the paired instrument in that same suite run said the
pass that had just changed was 1.42x faster, 39 of 40 rounds.

**Run B closes it.** With 1.2 GB free instead of 800 MB, the same test passes
inside the full suite: settled shots of 556, 509 and 402 ms, spread 154 ms. The
absolute figures are *worse* than run A's — the phone is warmer — and the test
passes anyway, because what it asserts is a spread and not a time.

## The same test a third time, and what its failure rate actually is

`repeatedCapturesTakeAConsistentTime` failed again on 3 September 2026, inside a
full suite run on a phone that had just been through one. This time it was
measured rather than reasoned about, because a change to `ZslRawStream` was in
the tree and the honest question was whether that change had caused it.

**Seven consecutive runs of the test alone, same phone, same session:**

| run | tree | result |
|---|---|---|
| 1 | changed | **fail** — slowest 674 ms against a 532 ms bound |
| 2 | changed | **fail** — slowest 654 ms against a 554 ms bound |
| 3 | **reverted** | pass |
| 4-6 | changed | pass, pass, pass |
| 7 | changed | **fail** — slowest 703 ms |

Three failures in seven, so roughly **40% on a phone in this state**: 41%
battery *and charging*, skin 40.7 C, little cores at 61 C, and 574 MB free of
15.9 GB after a full suite and a 1.1 GB mosaic assembly.

**The single reverted run is not the evidence, and it is worth saying why.** At
a 40% failure rate one pass has about a 60% chance of happening anyway, so
reverting-and-passing once demonstrates nothing at all. What rules the change
out is the other column: four passes in six on the changed tree. A regression
that adds 150 ms does not pass four times out of six.

The mechanism agreed afterwards, which is the right order to check it in: the
test drives `ZslRawStream` directly and never composes the UI, so the only new
caller in that change is never invoked during it.

**What the failures have in common is the bound, not the pipeline.** The
assertion is `slowest < fastest * 1.4` — a spread, as the section above says.
The failing ratios were 1.77 (674 against a fastest of 380) and 1.65 (654
against 396). Nothing was slow in absolute terms; the *fastest* shot stayed at
380-396 ms, which is quicker than run B's settled 402-556 ms. It is the
consistency that goes, exactly as it does under memory pressure in run A.

So the standing advice holds and now has a number behind it. Before suspecting
the code, check `/proc/meminfo` and the battery — and if the phone is warm and
charging, **one re-run is not a diagnosis.** Alternate the versions and count,
or wait for a rested phone, which is what this file has been saying about every
other figure in it.

# What grizzly costs

The Pixel 11 Pro's first reading, taken 4 September 2026. **This is a different
phone, not a third run of komodo's** — the two runs above are one device in two
states, and their whole argument is that absolutes moved 2x while ratios held.
Putting grizzly's milliseconds beside theirs would invite exactly the comparison
this file forbids. Compare the ratios below against komodo's ratios; ignore the
milliseconds except where they are the finding, which twice they are.

    Device: Pixel 11 Pro (grizzly), Tensor G6
    Build:  google/grizzly/grizzly:17/CD1A.260714.001.A9/15938155:user/release-keys
    Screen: 1280x2856 at 480dpi -- 426.7dp wide, against komodo's 448dp
    State:  35.5 C, unplugged and discharging, 82%, 2.7 GB available
    Cooled from 38.0 C over six minutes off charge before the run

## The A/As, which is why this reading is the one that counts

Three earlier attempts on this phone were taken warm and on charge, and their
A/As read 0.95x, 0.93x and 0.94x against komodo's 1.00-1.06x. Nothing measured
in that state is in this file. Off charge and cooled:

                        komodo run B        grizzly
    shading A/A         20 of 40, 1.02x     22 of 40, 1.02x
    prepass A/A         20 of 40, 1.00x     22 of 40, 1.07x
    tone A/A            23 of 40, 1.06x     18 of 40, 0.98x

That is the instrument agreeing with itself across two phones, and it is what
earns the right to read anything below.

## The paired ratios, against komodo's

                        komodo              grizzly
    the prepass fold    1.42x / 1.52x       **1.63x, 40 of 40**
    the shading hoist   1.52x / 1.57x       1.30x, 33 of 40  (see below)

**The fold is worth more on this phone, and it is the cleanest number here.**
40 of 40 rounds with a worst round of 1.03x -- it never once lost. Komodo's best
was 1.52x at 37 of 40. The fold's prize is DRAM traffic, so a memory system that
got faster making it win *more* is worth someone thinking about rather than
filing.

## The hoist has outrun its own test

    per-pixel   median 37ms, range 11-88ms
    hoisted     median 28ms, range  5-78ms
    hoisted won 33 of 40; per-round median 1.77x, worst 0.56x
    1.30x by paired median

The two summaries disagree -- 1.77x per round against 1.30x paired -- and the
raw rounds say why. The first fourteen rounds run at 11-17ms against 5-8ms, and
then everything jumps to 30-88ms. A regime change partway through, and in the
fast regime **the hoisted pass takes five to eight milliseconds.** At that size
the measurement is scheduling noise with a signal somewhere inside it.

So `hoistingTheGridOutOfTheLoop` now fails its round-count assertion on this
phone: it needs 36 of 40 and returned 33, 34, 35 and 37 across four runs, warm
and cold alike. **Cooling did not fix it, which is what rules out thermal state
as the cause.** The pass is not slower here -- per round it is 1.77x, better than
komodo ever read. It has become too fast to time.

The threshold is calibrated on komodo, where this pass took tens of
milliseconds. Do not relax it to get green; that hides the finding. Either give
the hoist more work per round so it clears the timer's noise floor, or drop the
round-count assertion for a paired-median one and say why.

### Resolved, 7 September 2026, by the second of those

The round count was left alone and then removed rather than relaxed. Eleven
further runs on grizzly put the count at 30, 31, 33, 34, 35, 36, 37, 38, 39 and
39 against its bar of 36 -- it passed roughly one run in three, which is a
coin's opinion and not a measurement. The A/A control stayed inside its 30-70%
band throughout, so the instrument was never the problem.

What replaced it is the median of the within-round ratios, which keeps the
pairing and is immune to the rounds the noise decides:

    A/A, the null       1.00x, 0.99x, 1.01x, 1.02x, 1.03x, 1.09x
    the hoist           1.53x, 1.62x, 1.68x, 1.72x, 1.74x, and 1.77x above
    the hoist on komodo 1.52x, 1.57x

The assertion is now `> 1.25x`, which sits between two measured populations
rather than beside the last run: noise cannot reach it from a null of 1.00, and
a working hoist cannot miss it from 1.53 at its lowest. Seven consecutive runs
of the class pass. The finding this section records is unchanged and still
true -- the pass has become too fast to time on this phone, and the per-round
figure remains the better one to quote.

## The tone ablation, at 82% of the frame above the knee

                                            komodo run A      grizzly
    everything after the demosaic           2.25x  16/16      1.65x  15/16
    renderLinear                            1.81x  16/16      1.55x  15/16
      its highlight desaturation            1.15x  14/16      1.34x  11/16
      its highlight roll-off                1.12x  13/16      1.14x  14/16
        the roll-off's lookup alone         1.10x  12/16      1.15x  11/16
    the whole display chain                 1.13x  11/16      0.99x   7/16
    the colour matrix                       1.05x  11/16      1.02x  10/16
    the display table                       1.02x   9/16      1.06x   9/16

And the three that are negative on purpose:

    the computed roll-off, against the table    0.71x  1/16    0.95x  6/16
    the scalar demosaic, against the vector     0.67x  0/16    0.84x  2/16
    the per-pixel demosaic, against both        0.61x  0/16    0.80x  2/16

**The vectorised demosaic wins by much less here.** 0.84x against komodo's
0.67x, and it read 0.85x on the warm run too -- the one figure that reproduced
across thermal states, which is what makes it a claim about the G6 rather than
about a hot phone. The scalar version now takes two rounds in sixteen; on komodo
it never took one.

**The display chain has fallen below the floor.** 0.99x at 7 of 16 is a coin
flip: on this phone it costs nothing measurable. Komodo read 1.13x at 11 of 16,
which was already marginal.

Scene dependence holds its shape and loses magnitude:

                                komodo              grizzly
    at 82% above the knee       1.87x, table 1.20x  1.65x, table 1.10x (12/16)
    at 1% above the knee        1.20x, table 0.92x  1.16x, table 1.03x (10/16)

The tabulated roll-off still pays at 82%, which corrects a reading taken warm
earlier the same day that had it at 0.97x and briefly looked like a finding.

## What it costs to reach the GPU here, on a different GPU

    Probe: PowerVR C-Series CXTP-48-1536 MC1, Vulkan 1.1, setup 24ms
    State: 33.5 C, unplugged and discharging, 77% -- komodo's run A was 33.1 C,
           so for once these two are directly comparable conditions.
    A/A on IMPORTED: 17 of 40, medians 13ms against 13ms. Sound.

**The GPU is not the same family.** Komodo's was Mali; this is Imagination
PowerVR, and it reports Vulkan 1.1 where a develop might want later features.
Check that before designing against it.

    route       komodo A   komodo B   grizzly     zero wrong pixels on all
    staging       37ms       77ms     102-127ms
    shared        36ms       50ms      75-84ms
    cached        10ms       20ms      16-22ms
    imported       7ms       16ms      10-11ms

**Every route is more expensive here, and that contradicts the reason this
measurement was deferred to this phone.** The argument for re-pricing the
crossing on new hardware was that a reported doubling of memory bandwidth acts
on the download, which is the crossing's whole cost. The download did not get
cheaper. On the cheapest route it went from 7ms to 10-11ms against komodo at a
comparable temperature, and the uncached shared route roughly doubled.

The ordering survives -- imported beats cached beats shared beats staging on
both phones -- but the spread widened sharply: importing is **9.05x** of the
uncached shared route here, 40 of 40, against komodo's 4.01x and 3.77x. On this
GPU the choice of route matters more than twice as much as it did.

**The prior question is still answered the same way, though.** 10-11ms of
crossing against a develop in the low hundreds of milliseconds is still a few
percent, so reaching the GPU is not what would make a GPU develop lose here
either. What has changed is the margin: the floor a real kernel gets added to is
half again what it was, on a GPU nobody has written a line for.

The develop cost this percentage rests on is measured now; see below, and
note that it changes the answer.

## What a photograph costs here

Read out of real captures on 4 September 2026, at 92% and 990 MB free, on the
build that carries the overlapped-tile merge. This is the figure the GPU
section above says it is carrying from komodo, and it replaces it.

    develop:  prepass 17-18ms, demosaic+tone 24-26ms, total 41-45ms
    sharpen:  luma 2-4ms, sharpen 10-12ms, total 15ms
    stages:   autoexposure 1-2ms, bitmap 0-1ms, render 42-45ms,
              defringe 0ms, sharpen 16ms
    breakdown: setup 1ms, native 63ms, rotate 0ms, encode 48ms, publish 30ms

    merge over 3 alternate frames:
              proxy 17-22ms, pyramid 0ms, align 41-74ms, accumulate 38-52ms

**Komodo's develop was 112-199 ms rested and 152-279 warm; this one is 41-45.**
Do not read that as a speedup of three, and do not read it as anything at all
without the caveat this file exists to make: those are different phones in
different states measured on different days, which is the comparison the rules
forbid. What it does settle is the GPU question's denominator. The cheapest
crossing here is 10-11 ms against a develop of 41-45 ms -- **a quarter of a
develop, not the 5-6% carried from komodo.** The prior question that the
crossing test was built to answer therefore comes back with a different answer
on this phone: reaching the GPU is no longer obviously cheap enough to ignore,
and a GPU develop starts a quarter of the way behind before its kernel runs.

The accumulate figure is the overlapped-tile merge and is about 1.35x what the
old one cost -- see the session log for the alternated measurement. The old one
read 26-51 ms over the same three frames.

## What is not here

**The full suite now completes: 127 of 128.** The single failure is
`hoistingTheGridOutOfTheLoopIsFasterAndDoesNotChangeThePicture` at 35 of 40
rounds, which is inside the 33-37 band this phone has given warm and cold alike
and is a test that has outrun its own timer rather than a regression. Every
parity test passes, including both merge parity tests and all three widths of
the varying-field one.

An earlier attempt stopped at 87 of 128 when wireless adb dropped. What fixed it
was `adb shell svc power stayon true` before the run; the link drops after idle,
and a suite that takes eight and a half minutes idles the screen well before the
end.

Still missing: nothing about correctness, and nothing about the develop. What is
left is a judgement rather than a measurement -- **nobody has looked hard at a
batch of real photographs from this sensor**, which is the item the handover has
carried longest and the only one that can say whether any of this is a picture.
