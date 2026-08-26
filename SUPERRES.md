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
| Capture planning and geometry | Done | 12 unit tests |
| Native compositing canvas with feathered blending | Done | 8 device tests; seam step 1/255 |
| Guided capture map | Done | 3 Compose tests; coverage drawn at canvas shape |
| Exposure, white balance and focus locking | Done | `lockForSweep`; a device test asserts the stream is handed back |
| Writing the result out | Done | 3 device tests; compresses from the canvas's own pages |

Every piece of it is built. What has not happened is the part no table can
record: nobody has swept a real scene with it. The stitching is tested against
synthetic frames cut from a generated image, which is the right way to test the
algorithm and no substitute for pointing it at a building.

## How the capture should work

1. User frames the shot on the target lens and presses capture.
2. App switches to the capture lens, **locks exposure, focus and white balance**
   — without this, tiles differ in brightness and the blend cannot hide it.
3. A grid overlay shows which cells are covered. The user sweeps; the app takes
   a frame whenever the view enters an uncovered cell and is steady.
4. Each frame is registered against its neighbours, not against a global
   reference: accumulated error is smaller chained locally.
5. Tiles composite into the canvas as they arrive, so memory holds one tile plus
   the canvas rather than all forty-nine.
6. Coverage below a threshold at the end means an honest partial result, cropped
   to what was shot, rather than black bars.

Step 4 is where a future refinement belongs: **bundle adjustment**, jointly
optimising all tile transforms rather than chaining pairwise fits, which is
what keeps a large mosaic from drifting. Pairwise chaining is good enough for
7 × 7 and would not be for 28 × 28.

## Why this fits the app

It is the only feature on the roadmap that produces a result a phone camera
demonstrably cannot, rather than a better version of one it can. Everything
else here — the burst merge, the raw pipeline, the tone curve — is competing
with the stock camera on its own ground. This is not.

It also stays inside the app's argument. No detail is invented; the resolution
comes from photons that were actually collected, by a lens that was actually
pointed at that part of the scene.
