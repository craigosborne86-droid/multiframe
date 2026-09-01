# What komodo cost

The Pixel 9 Pro XL's last full reading, taken deliberately before the phone
changes. A Pixel 11 Pro replaces it, and **every figure in
[SESSION-LOG.md](SESSION-LOG.md) was measured here** — so this file exists to be
the thing the new phone is compared against, and to say plainly which parts of
it are allowed to be compared at all.

## What this was measured on

| | |
|---|---|
| Device | Pixel 9 Pro XL (`komodo`) |
| Build fingerprint | `google/komodo/komodo:17/CP2A.260805.005/15828068:user/release-keys` |
| Commit | `d40f7fd` |
| APK on the phone | md5 `8d55e9d3614fecb1db46355db54c9e75`, verified against the local build |
| Date | 1 September 2026 |

**And in what state, which is not a footnote.** This project has recorded twice
that a figure without its device state beside it is not a figure:

    before   45%, not charging, 33.1 C, 800 MB free of 15.5 GB, 1.5 GB swap free
    after    43%, not charging, 36.9 C, 543 MB free

That is a *rested but memory-pressed* phone. The log's own note is that at around
850 MB free, rounds that take 110-160 ms come back at 400-780 ms about one in
three, at random — so the absolute milliseconds below read high and one test
failed on exactly that. The paired comparisons are unaffected, which is the
whole reason this project prefers them.

## How to use this file

- **The paired ratios transfer. Nothing else does.** They are two
  implementations timed against each other inside one process, alternating
  within a round, so the phone's wander cancels. Compare the new phone's ratios
  with these.
- **The milliseconds do not transfer, and are here to be read as shape** — which
  stage dominates, how wide the spread is — not as a target. The Tensor G6 is
  reported to pair with roughly twice the memory bandwidth against a much
  smaller gain in the cores, which is enough on its own to move some of the
  ratios below. See *When the phone changes* in [HANDOVER.md](HANDOVER.md).
- **Never subtract a figure here from one taken in another run.** That is the
  rule this project has broken most often and it is the reason this file is
  dated, stamped and committed rather than remembered.

## Correctness

**359 unit tests pass. 123 of 124 device tests pass**, the exception being a
capture-consistency failure that is device state — evidence below.

Every C++ stage is pinned to its Kotlin reference and all of those pass here:
develop (worst 0/255), sharpening, shading, alignment, rotation, the merge, and
the JPEG encoder against the framework's.

## The capture path

What a photograph actually costs, from `ZslStreamDeviceTest`, seven settled
shots:

    prepass          41, 54, 61, 66, 74, 84, 85 ms
    demosaic+tone    71, 80, 85, 96, 97, 100, 113 ms
    develop total    112 - 199 ms
    luma + sharpen   34 - 65 ms

The develop is two passes as of this commit, where it was four. `prepass` is
`black`, `hotpixels` and `shading` folded into one sweep.

## The paired instruments, which are the ones that transfer

    the shading hoist            1.52 - 1.56x    40 of 40 rounds
    the prepass fold             1.42x           39 of 40 rounds
      its A/A                    1.01x           20 of 40 rounds

`ToneAblationDeviceTest`, sixteen rounds each, as paired ratios — a figure above
1 means the item costs that much of the pass:

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

    A/A (tone harness)           0.99x   19 of 40 rounds
    A/A (shading harness)        0.92x   13 of 40 rounds

The shading A/A is the weakest reading in this file: 13 of 40 clears the 12 the
test demands and no more. On a memory-pressed phone that is expected and it is
worth watching on the new one, because an A/A drifting off centre invalidates
everything measured beside it.

## The isolates, which are the weakest instrument here

Single implementations, no second slot, and they cannot compare anything. The
develop one is known to read about twice what a capture pays and nobody has ever
explained why.

    develop at 4080x3072    median 225ms, 200-267ms over 9 rounds
    sharpen at 4080x3072    median  37ms,  31-43ms over 9 rounds
    merge, per frame        median 199-207ms, 176-234ms
    JPEG, strip encoder     134ms at 1912 KB, against the framework's 63ms at 2013 KB

## The one failure, and why it is device state

`repeatedCapturesTakeAConsistentTime` failed in the full suite:

    develop across four shots           521, 351, 2906, 354 ms
    of which MediaStore publish         136,  76,  309,  59 ms
    develop less publish, warm-up shot dropped   275, 2597, 295 ms

One shot in three at nine times its neighbours, which is the memory-pressure
signature the log describes and not the MediaStore publish that caused the four
earlier failures — this figure already has the publish subtracted.

**Checked rather than assumed.** Run standing alone, twice, immediately
afterwards:

    415, 454, 431 ms   spread 39 ms
    491, 428, 477 ms   spread 63 ms

Both pass. Three things say this is not a regression: the two neighbouring shots
in the failing run were 275 and 295 ms, which is *fast*; the spread standing
alone is 39 and 63 ms against a 2322 ms spread in the suite; and the paired
instrument in the very same suite run says the pass that changed this session is
1.42x faster, 39 of 40 rounds. A regression does not produce one outlier in
three and leave the rest quick.

Note also that the settled figure reads 275-295 ms in one run and 415-491 ms
twenty minutes later. Both are true, neither is transferable, and that is the
point of this file.
