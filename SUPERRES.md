# Super-resolution by telephoto mosaic

> **Is a feature like this possible?** Yes. It is well-founded rather than
> speculative, the mathematics is exact rather than approximate, and the core
> of it is built and tested. It has one real limitation, which is a property of
> the world and not of the implementation.

## The idea

The 110mm lens sees a quarter of the width the 24mm lens does. Sweep it across
the 24mm framing, stitch the frames, and you have that framing at roughly
twenty times the pixel count — with no invented detail, because every pixel was
measured by a sensor.

This is not upscaling and it is not generative. It is the same thing an
astronomer does with a mosaic, or a landscape photographer with a stitched
panorama, pointed at a different goal: instead of a wider view, a *denser* one.

## What the numbers actually are

Computed by `MosaicPlanner`, from this device's real lens geometry:

| Target framing | Capture lens | Tiles | Result | Linear gain |
|---|---|---|---|---|
| 24mm | 110mm | 7 × 7 | **263 MP** | 4.6× |
| 24mm | 220mm | 13 × 13 | 1052 MP | 9.2× |
| 12mm | 220mm | 28 × 28 | 4213 MP | 18.3× |

For comparison, the main camera produces 12.5 MP. The 7 × 7 sweep is the one
worth shipping: 49 frames is a few seconds of sweeping, and 263 MP is already
past what any display or print will use.

The canvas is the sobering part. 263 MP costs 2 GB of working memory, on a
phone that had 1.4 GB free. So the planner carries a cap that preserves framing
while reducing area, defaulting to 80 MP at 610 MB.

Writing the result used to cost that again. Saving went through a `Bitmap` of
the whole canvas -- 320 MB at the default cap, on top of the 610 MB the canvas
already held -- and `MosaicSession.save` carried an `OutOfMemoryError` branch
that returned no photograph at all when it failed. It now compresses out of the
canvas's own pages: the accumulator is collapsed in place into packed RGBA,
four bytes a pixel over the eight it replaces, and the JPEG goes down a file
descriptor in pieces as the compressor produces it. Nothing image-sized is
allocated to save an image.

That does not raise the ceiling -- the canvas is still what the phone cannot
afford, and 263 MP still needs 2 GB to composite into. It means the result the
phone *can* hold is one it can also write.

## Why it works: the mathematics is exact

For a camera **rotating about its optical centre**, the mapping between any two
views is a homography — an eight-degree-of-freedom projective transform — with
no residual error, *whatever the scene geometry*. This is not an approximation
that holds for flat subjects. It is exact for arbitrary three-dimensional
scenes, because rotating the camera changes which rays reach the sensor but not
which rays exist.

That is the whole foundation. It is why panorama stitching works at all, and it
is why this is a solvable problem rather than a research project.

The burst aligner already in the app cannot do this. It estimates a translation
per tile, which is right for a hand-held burst where the camera barely rotates
over a third of a second. Sweeping a lens is a different motion entirely:
rotation makes parallel lines converge and scale vary across the frame, and no
amount of translation fits that.

## The one real limitation

**Parallax.** The exactness above requires rotation about the optical centre.
Hand-held panning also *translates* the camera by a few centimetres, because
the phone rotates around the wrist rather than around the lens.

For a distant subject that displacement is negligible. For a near subject it is
not, and no homography can fix it — objects at different distances move by
different amounts, which is depth information, not a registration error.

So this works for:

- landscape, cityscape, architecture
- anything beyond roughly ten metres
- flat subjects at any distance (artwork, documents, walls)

and does not work for:

- close subjects with depth
- anything moving
- scenes where near and far objects overlap

This is the same reason phone panoramas tear on a nearby railing. It is honest
to tell the user this in the interface rather than let them discover it.

## What is built

| Piece | State | Where the evidence is |
|---|---|---|
| Projective registration (DLT, Hartley normalisation) | Done | 14 unit tests |
| RANSAC robust fitting | Done | Recovers exactly with a third of matches poisoned |
| Corner detection and matching | Done | 8 unit tests, recovers known rotation |
| Match verification | Done | Brown-Lowe criterion; refuses noise and repeated patterns |
| Capture planning and geometry | Done | 12 unit tests; presets 2x2, 3x3 and a drawn region, 7 more |
| Native compositing canvas, three-band blending, the detail band by seam cut | Done, the seam after the seventh field test | One tile is identical to the byte in one band and three; two tiles a quarter-stop and a pixel apart keep their texture at one tile's contrast and lose their step; a feature one tile has and the other lacks comes through whole, never as a half-moon (`MosaicBlendDeviceTest`) |
| Two engines on one pill: the one-shot 2x canvas on any lens, and the telephoto grids | Done | `MosaicPreset.ONE_SHOT`; a sixteen-frame burst through the same develop queue as any shot; the pill and its hint tested on the device |
| Reticle and dot guidance, from the attitude, with a stability gate; the burst exposed after the lock | Done, revised after five field tests | 18 unit tests on the lock rule against scripted pans, including a hand still settling and a gyroscope veto; 8 on the attitude geometry, turning the phone by known angles and reading the dots -- `f · tan` to a twentieth of a pixel, and a node that stays where the world put it; the overlay draws the canvas outline and each node where the attitude puts it, and nothing moves a dot |
| Angular grid at thirty percent overlap, hole-free write | Done | The planner's `gridGeometry`: 4 unit tests on the step, the canvas reach and the seventy-degree limit; the canvas writes the largest fully covered rectangle, read back with no black pixel (`MosaicCanvasTest`) |
| Two-tier placement: registration, then the attitude | Done, after the fourth field test | A node is done when its burst is in hand; the tile is placed by its pixels where they fit believably (ten inliers, within a quarter of a tile of the attitude, a tile's size) and by the attitude's transform onto the canvas plane otherwise. The live device test requires a dark run to complete by the attitude |
| Refinement against the canvas before a tile is drawn | Done, after the eighth field test's ghosted tower | Either tier can be a hundred pixels out; where the tile overlaps tiles already drawn, block-matched normalised cross-correlation coarse to fine measures by how much, and the correction -- a translation, or a homography from eight agreeing blocks -- is composed onto the placement. `MosaicRefineDeviceTest`: a tile a hundred pixels off and a quarter of a tile off comes back to within two; a tile placed right is left alone; 40 ms a tile |
| Low-light shutter stretch | Done | Where the floor would need more than ISO 3200, a still hand (< 4°/s) gets the floor stretched toward 1/60 s and the gate tightened to match; 1 unit test walks the evening case |
| The mode's lifecycle and the back gesture | Done, after the second field test's deadlock | `SuperResMachine` walked stop → re-arm → run by 7 unit tests; `BackNavigation` as a rule (6) and as plumbing on the device (2) |
| The shutter floor | Done | 1/250 s at 105mm, scaled by focal length, ISO raised to keep the light; 3 unit tests |
| Burst per node, merged and lens-corrected | Done | The same merge as a shutter press, the Phase 2 optics before the homography |
| Exposure, white balance, colour and develop gain locking | Done | `lockForSweep`; colour and gain fixed by the anchor, tiles normalised by ISO × exposure |
| Writing the result out | Done | 3 device tests; compresses from the canvas's own pages |

Every piece of it is built, and it has been pointed at a real scene twice:
the second field test produced a genuine full-colour 33 MP canvas, and found
the app around it unusable in the hand -- which is what the lifecycle, the
attitude guidance and the gate's second calibration answer. The stitching is
also tested against synthetic frames cut from a generated image, which is the
right way to test the algorithm.

## How the capture works

1. The user selects SUPER RES in the mode carousel under the shutter, on
   whatever lens they are on. The pill above the shutter offers the two
   engines -- `1-SHOT · 50 MP | 2×2 · 33 MP | 3×3 · 75 MP` -- with a word
   under the count about what each is for. **1-SHOT** is not a mosaic: a
   single tap takes a sixteen-frame burst on the lens in hand and
   accumulates it on the 2x sub-pixel canvas from the hand's own tremor,
   four times the sensor's pixels at any distance with nothing to stitch;
   the lens strip stays offered for it. For a **grid** the app switches to
   the longest raw lens and says so, switches the raw ring on if it was
   off, and draws the outline of the whole picture and its tile nodes on
   the viewfinder. Nothing has been taken: the user is composing. Another
   mode, or the back gesture, puts the lens and the ring back.
2. The shutter anchors a grid. The app waits for the camera's exposure and
   white balance to converge, **locks exposure, focus and white balance** as
   the sensor's own settings -- a manual exposure at the meter's values, or
   at 1/250 s with the ISO raised if auto exposure had chosen slower, or, in
   the dark on a hand still under four degrees a second, that floor
   stretched toward 1/60 s so the sensor is not starved; the focus held at
   the distance the lens was at, so no tile refocuses -- and takes the
   anchor: wherever the reticle points becomes the
   centre of the canvas, the develop gain and colour are measured from that
   frame and held, and the nodes are laid out around it as directions in the
   world, from the phone's attitude at that instant.
3. A reticle sits at the centre of the viewfinder and one target is active
   at a time -- the nodes are taken top-left, top-right, bottom-right,
   bottom-left, a serpentine over the rows -- drawn where the phone's
   attitude says that direction now falls in the frame, thirty times a
   second, from the rotation vector, whatever the scene shows, with an arrow
   from the reticle toward it. The dots are static targets in the world, as
   a panorama's are: a dot laid over a doorknob stays on the doorknob as the
   phone pans, and nothing that happens afterwards moves it. The user follows
   the arrow onto the dot and holds *still*: the ring around the dot fills
   over 150 ms and empties the moment the gyroscope reads more than six
   degrees a second or the reticle drifts more than that would move it. The
   ring closes -- and *then the burst is exposed*. Not a frame already in
   the raw ring is used: those were exposed while the hand was arriving, and
   four field tests' tiles carried that blur. The ring around the dot fills
   a second time, in the confirm colour, as four to six fresh frames are
   exposed after the lock with the gate still held -- a turn discards the
   frames exposed during it and starts the count again; leaving the dot
   gives it back -- about a quarter of a second the hand holds for on
   purpose, with EXPOSING · HOLD under the count. Then the node is done, the
   haptic fires (after the last frame, so the motor cannot reach it), a
   pulse expands from the reticle for a beat, the dot becomes a check and
   the arrow turns to the next. Pan too fast and the phone says so. STOP,
   or the back gesture, ends the capture keeping what was taken.
4. The node is done the moment the burst is in hand: the checkmark, the
   haptic, the next dot. On its own thread the burst is merged like any other
   photograph, developed with the anchor's colour and gain (the gain scaled
   by its own ISO and exposure), corrected for the lens's geometry, and
   composited -- where its reference frame registered against the anchor and
   every tile so far, if that fit is believable (ten inliers, within a quarter
   of a tile of where the attitude says the phone pointed, a tile's size),
   and otherwise where the attitude puts it: the tile's rectangle of
   directions met with the canvas plane. A dark frame, a blank wall, a patch
   of sky all place by the attitude. Nothing a registration says can refuse
   a node. Then, before the tile is drawn, the canvas itself measures the
   placement: where the tile overlaps tiles already there, its pixels are
   matched against theirs block by block, and the placement is corrected by
   what they say -- a hundred pixels, a quarter of a tile, whatever either
   tier was out by (REFERENCE §3.4.4). The eighth field test's tower stood
   three times in its sky before this; no blend hides a misplacement of
   that size, and none has to.
5. Compositing is in three frequency bands: the fine detail comes from one
   tile at every pixel, the boundary between tiles a *seam* cut where the
   two disagree least -- along a sky, a shadow, an edge that registered,
   never across a texture they place a pixel apart -- with the deeper tile
   preferred and a hand-off two and a half pixels wide; the broad
   differences between them are spread over six hundred pixels. The texture
   is kept whole and the step is hidden. A single tile composes exactly as
   it always did.
6. The count reads "3 of 9 nodes". Every node captured is the mosaic complete;
   stopping early keeps what was taken. The file is the largest rectangle
   with no hole in it -- nine hand-held tiles are nine slightly turned
   rectangles, and the box around their union has corners nothing reached,
   which a JPEG would paint black.

Bundle adjustment -- jointly refining every tile's transform against every
overlap -- stays where it was: not built, because a 3x3 registered against all
its neighbours does not drift enough to need it, and it would be machinery in
service of a grid nobody should be taking.

## Why this fits the app

It is the only feature on the roadmap that produces a result a phone camera
demonstrably cannot, rather than a better version of one it can. Everything
else here — the burst merge, the raw pipeline, the tone curve — is competing
with the stock camera on its own ground. This is not.

It also stays inside the app's argument. No detail is invented; the resolution
comes from photons that were actually collected, by a lens that was actually
pointed at that part of the scene.
