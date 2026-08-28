# Ideas, for discussing

Not a plan and not a backlog. These are things worth talking about before any
of them is built, written down while the code is fresh so the reasoning is
attached to them.

Each says what is already in the repo, because most of these are closer than
they look — this app has an aligner, a merger, a noise estimator, a native
developer and a mosaic stitcher, and several "new features" are a different
weighting over machinery that already exists and is already pinned by tests.

Marked **[grounded]** where I checked the code, **[speculative]** where I did
not.

---

## 1. The app's whole argument is invisible

**This is the one I would do first.**

The thesis of this camera is that many raw frames beat one. It *measures* that,
per capture, in three ways: `framesMerged`, `meanContribution` (how much of the
burst survived rejection) and `estimatedSigmaAtMid` — the noise the frames
actually disagreed by, measured from the burst rather than assumed. **[grounded]**

All three go to logcat and nowhere else. A user sees a photograph appear and has
no way to know whether they just got the benefit, how much of it, or whether the
merge silently fell back to one frame because everything moved.

What that could be, in rising order of ambition:

- **A line under the shutter after a capture.** `12 frames · noise ÷3.4`. The
  numbers exist; this is a readout, not a feature.
- **A quality reading before the shutter**, from the ring: how many frames are
  currently mergeable, so a user learns that bracing against a wall changes the
  answer.
- **Written into the file.** The EXIF writer already records the frame count.
  The measured noise reduction belongs there too — it is the one number that
  says what this app did that another would not.

The risk is turning a camera into a dashboard. The mitigation is that all of it
belongs in the review of a shot that has been taken, not over the viewfinder.

## 2. A/B is the proof and it is currently a two-letter chip

`abMode` writes both the merged and the unmerged version of the same burst.
**[grounded]** That is the demonstration of everything above, and right now it
is an unlabelled toggle with no explanation, no persistence, and no way to see
the two results against each other except in the system gallery.

Worth discussing: a comparison view — the same frame, merged and not, with a
slider — as the thing you show someone to explain the app in five seconds.

## 3. Three features that are re-weightings of machinery already here

The merge aligns frames and combines them, rejecting pixels that disagree
because disagreement means motion. Change what "disagree" means, or what is
varied between frames, and it becomes something else entirely.

- **Exposure bracketing → HDR.** Vary exposure across the burst instead of
  holding it, and weight by well-exposedness rather than agreement. The ZSL ring
  already holds a burst; the aligner already handles hand-held drift. The hard
  part is not the merge, it is that raw HDR needs the tone curve to know it is
  compressing a wider range — which is the one part of the pipeline that is
  already a single global curve with a shoulder. **[speculative]**
- **Focus bracketing → focus stacking.** Same alignment; per-tile select by
  sharpness instead of average by agreement. The app already computes a
  sharpness metric for focus peaking, and already has manual focus in
  dioptres. **[grounded on the parts, speculative on the merge]**
- **Astro stacking.** This is the same operation as the merge, run for much
  longer, and it is what the name *Coadd* in [NAMES.md](NAMES.md) refers to.
  Needs: tripod detection (the app already tracks attitude for the level guide),
  much longer exposures, and star-aware alignment rather than tile
  translation. **[speculative]**

And a fourth that is nearly free and quite different: **not rejecting**. The
merge exists to throw away pixels that moved. Summing them instead is light
trails and smoothed water — a long exposure without the blown highlights a long
exposure gives you. That is close to a flag on the accumulator. **[speculative]**

## 4. Gaps that will bite someone

- **The timer, the guides and A/B do not persist.** `AppSettings` carefully
  saves manual exposure, focus, white balance, EV, ISP suppression, burst count,
  lens, merge, guard, ZSL and capture mode — and not those three. **[grounded]**
  A self-timer that forgets is worse than no self-timer, because you find out
  by missing the shot.
- **Nothing can be cancelled except a sweep.** A 32-frame night capture commits
  you for several seconds with no way out. **[grounded]** The sweep got a stop
  control precisely because this was intolerable there.
- **What happens if a capture is interrupted** — a call, the screen locking, the
  app going to the background mid-burst — is not tested. **[grounded: no such
  test exists]**
- **Storage full, or the permission revoked mid-session.** The failure path
  writes a status string; whether it is a *good* status string has never been
  looked at.

## 5. Interface, still open

- **The settings row is nine long and a phone shows four.** `A/B`, `ZSL` and
  `GUARD` are engineer-facing; moving them into the `PRO` panel would leave a
  photographer-facing row of five that fits on screen, and give `PRO` a clear
  meaning: *what you set once*, against *what you change between shots*. I did
  not do this unilaterally because it changes where you reach for things you use
  daily. **[grounded]**
- **No exposure or focus lock.** Touch-to-focus exists; the long-press that
  locks it is the universal gesture and is missing. **[grounded]**
- **The histogram is always on**, with no way to turn it off, while the grid and
  level are a cycling control. **[grounded]**
- **No haptics.** A shutter and a cycling value are both things a thumb should
  feel, especially when the eye is on the scene.
- **Landscape has never been looked at.** The control row, the action strip and
  the lens strip are all horizontal scrollers laid out for a portrait phone.

## 6. The thing that still needs a person

Unchanged from the handover, and it outranks everything above: **nobody has
swept a real scene with the mosaic.** Every stitching claim in this repo rests
on synthetic frames cut from a generated image. That is the right way to test
the algorithm and it is not evidence about a building.
