# Multiframe — build log

A record of the session that built this app, written 2026-08-24.

**Multiframe** is an Android computational photography camera. Every shutter
press captures a burst of frames, aligns them to cancel handshake, and merges
them to reduce sensor noise. Inspired by the general technique Adobe's Project
Indigo demonstrates on iPhone; original code, name, icon and UI throughout.

- Package: `dev.multiframe.camera` (final — cannot change after publication)
- Target device for development: Pixel 9 Pro XL (`komodo`), Android 17 / API 37
- ~20,000 lines across 90 Kotlin files and 6 native files
- 294 JVM unit tests and 79 on-device tests (66 running, 13 awaiting an unlocked screen)

---

## Toolchain (all verified by running it, not assumed)

| Component | Version | Note |
|---|---|---|
| JDK | Temurin 21.0.12.1 LTS | Installed this session; none was present |
| Gradle | 9.5.0 | |
| AGP | 9.3.2 | Kotlin support is **built-in**; `org.jetbrains.kotlin.android` is now an error |
| Kotlin | 2.4.10 | |
| compileSdk | 37 | Forced by AndroidX 1.19.x |
| targetSdk | 36 | Play requires Android 16 from 31 Aug 2026 |
| minSdk | 33 | AGSL/`RenderEffect` floor, modern MediaStore |
| CameraX | 1.6.1 | incl. stable `camera-compose` |
| NDK | 28.2.13676358 (r28c) | AGP 9.3's documented default |
| CMake | 3.31.1 | |

### Environment gotchas

**The project volume is exFAT.** macOS writes AppleDouble `._*` sidecars on it,
and AGP fails on them (`'._drawable' is not a directory`). Build output is
redirected to `~/Library/Caches/MultiframeBuild/` on the internal APFS volume.
Sources still accumulate them; clean before building:

```bash
find . -name '._*' -delete
```

**Disk is tight.** The internal volume sits at ~96% (8.6 GB free) after the
2.8 GB NDK install.

```bash
export JAVA_HOME="$HOME/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home"
./gradlew :app:assembleDebug
```

---

## Phases 0–5 (the original brief)

### Phase 0 — Environment
JDK absent entirely, no shell profile, no `ANDROID_HOME`. Installed Temurin 21
and created `~/.zshenv` (not `.zshrc` — zsh only reads that for interactive
shells, so Gradle would still have seen no `JAVA_HOME`). Cloned Google's
Android skills repo for the CameraX guidance.

### Phase 1 — Working camera
Compose UI, `CameraXViewfinder`, MediaStore capture. Proven on device:
permission dialog, live preview, capture saved and indexed.

### Phase 2 — The merge
- `BurstBuffer`: rolling ring of preallocated YUV frames → zero shutter lag
- `Aligner`: hierarchical coarse-to-fine tile matching on luma
- `Merger`: per-pixel robustness weighting, accumulation in linear light
- Restrained global tone curve; **no spatial denoising anywhere**

Measured on device, same-burst A/B, high-contrast scene:

```
shadow noise sigma   3.588 -> 1.982   1.81x
global contrast      81.309 -> 81.308  (unchanged)
```

### Phase 3 — Manual controls
ISO, shutter, focus distance, white balance, EV, burst count — all gated on
runtime `CameraCharacteristics`. Verified by **measured hardware response**,
not screenshots: shutter sweep moved viewfinder luma 92.6 → 251.2; ISO sweep
85.6 → 174.4 with shutter pinned.

Device reports: `manual  ISO 22-11277  26us-16000ms  focus 0-9.5D  RAW`

### Phase 4 — Portability
Four real assumptions removed: unconditional ISP noise-reduction/edge-off
requests, unconditional `CONTROL_AF_MODE_CONTINUOUS_PICTURE`, assumed back
camera, and fixed frame count/resolution. Both are now derived from
`Runtime.maxMemory()`.

### Phase 5 — Play Store
Release signing (gitignored keystore), R8 (**64 MB debug → 2.5 MB release**),
privacy policy plus an in-app About screen, store assets, listing copy.

---

## Beyond the brief: the raw pipeline

The JPEG path merged gamma-encoded YUV. Indigo merges **raw**, which is what
lets its DNG and JPEG "benefit equally". So the pipeline was rebuilt around a
raw front end.

### Architecture (matches what Indigo describes)

```
raw burst → align → merge (Bayer domain) → merged CFA
                                             ├→ DNG   (written untouched)
                                             └→ develop → JPEG
```

One merge, two outputs. Nothing is merged twice.

### The Bayer-specific problem

Alignment offsets **must be even**, or a red sample lands on a green one and
colour is destroyed. Solved structurally rather than by checking: alignment runs
on a half-resolution luma proxy built from each 2×2 CFA cell, so doubling its
offsets yields even full-resolution offsets *by construction*. The proxy needs
no knowledge of the pattern order, since every cell holds one red, one blue and
two greens.

Verified — colour means drift by under one code through a shifted merge:

```
R 329.2 → 328.4    G 569.3 → 568.4    B 219.3 → 218.4
```

### Native rewrite (NDK)

The binding constraint was the managed heap: **256 MB of Java heap on a 15.2 GB
device**, against 25 MB per raw frame. That caused a real OutOfMemoryError.

```
dalvik.vm.heapgrowthlimit   256m
dalvik.vm.heapsize          512m     (all largeHeap would give)
MemTotal                    15.2 GB
```

Now: accumulators are `std::vector` in native memory; camera frames are read in
place via `GetDirectBufferAddress`; the DNG is written straight from the native
buffer; develop writes directly into the Bitmap's pixel store via
`AndroidBitmap_lockPixels`. **No Java array anywhere in the pipeline.**

```
created 4080x3072 accumulator, 119.5 MB native
```

Alignment deliberately stayed in Kotlin — small, and already covered by tests.

### Results

| Stage | Before native | After |
|---|---|---|
| Capture | 1716 ms | 1649 ms |
| Merge | 4245 ms | **1884 ms** |
| Develop | 1903 ms | **544 ms** |
| Write | 199 ms | 240 ms |
| **Total** | **~8060 ms** | **~4317 ms** |

Correctness held: native gives **2.07×** flat-region noise reduction against
Kotlin's **2.06×** on the same kind of scene.

---

## Mistakes made and corrected

Recorded because several produced confident, wrong numbers.

**An invalid A/B comparison.** The first on-device merge comparison used two
separately triggered captures 37 seconds apart, and the phone moved between
them — a shadowed pillar compared against a sunlit building. The measurement was
meaningless. This is why A/B mode now derives both images from one
`snapshot()` call, with a same-scene correlation check in the evidence.

**A broken DNG extraction.** Reported 4.25× improvement and noise sigmas above
600 codes in a 10-bit file. `StripOffsets` is an array of 3072 entries
(`RowsPerStrip` = 1); the tag holds a *pointer* to the offset table, which I was
parsing as pixels.

**Texture counted as noise.** The corrected extraction then reported only 1.22×.
The metric took each pixel's residual against its neighbours, which captures
scene detail as well as noise. Restricting to the flattest 20% of blocks gave
the real 2.06×.

**A broken aligner, caught by tests.** Coarsest-level tiles were 4×3 px — too
little texture — so some tiles locked onto spurious matches producing offsets of
−32, −38, −47 px that finer levels could not recover from. Fixed with a minimum
tile size and neighbour-candidate seeding.

**A fixed robustness threshold.** Sensor noise is signal-dependent, so at ISO
6712 the weighting read noise as motion and rejected good frames — the pipeline
was weakest exactly where multi-frame capture matters most. Replaced with a
noise model estimated from the burst itself: 1.31× → **1.89×**.

**An OOM from not releasing buffers.** Wrote the DNG then died allocating the
render buffer, because 100 MB of accumulators were still live.

**A crash that was good news.** `RAW_JPEG` fires the capture callback twice,
JPEG usually first. Resuming on the first image crashed with "Already resumed" —
which proved raw *was* being delivered, the opposite of my first reading.

---

## Known limitations

- **Sequential capture is still the bottleneck at 1649 ms** on the CameraX
  path, with frames ~870 ms apart over a 5.6 s window. Phase 6 replaces this
  with a streaming ring on hardware that allows it; this remains the fallback,
  and remains too slow for moving subjects.
- **The merge is now the bottleneck at 1884 ms**, which is what Phase 7
  addresses.
- **The YUV path still exists** alongside the raw path, at 3.1 MP with assumed
  BT.601 range and sRGB gamma.
- **Portability is tested against simulated capability profiles**, not real
  Samsung or Oppo hardware. That needs a second physical device.

---

---

## Phase 6 — Zero-shutter-lag raw ring buffer

The previous section ended by calling capture "the bottleneck at 1649 ms", with
frames arriving ~870 ms apart because CameraX cannot stream `RAW_SENSOR`. This
phase removes that.

### Why this could not stay on CameraX

CameraX has no way to carry raw on a repeating stream. `ImageAnalysis` emits
`YUV_420_888` or `RGBA_8888` only, and `Camera2Interop.Extender` can set request
keys and attach callbacks but cannot add an output surface to the session
CameraX builds. A rolling raw ring needs the raw `ImageReader` to be a target of
the **repeating** request, which means a capture session this app owns.

The camera admits one client, so engaging the ring unbinds CameraX and feeds the
viewfinder from a Compose `AndroidExternalSurface` instead. Everything else is
untouched: when the policy declines, the CameraX sequential path runs exactly as
it did before.

`SurfaceView` rather than `TextureView`, deliberately. Camera2 tags preview
buffers with the sensor-to-display rotation and SurfaceFlinger honours that hint
for a SurfaceView; a TextureView ignores it and shows the viewfinder on its side
until given an explicit matrix.

### The decision is the hardware's, not the developer's

`ZslPolicy` takes the raw output configurations and returns either a stream size
or a reason not to. The decisive field is the stall duration: non-zero means
producing a raw frame blocks the other streams, so streaming it continuously
would stutter the viewfinder. It is deliberately free of Camera2 types, which is
what lets eight unit tests cover profiles this device does not have — a sensor
that stalls, one that streams too slowly, one where only the full-resolution
mode stalls so a binned mode should win.

What the Pixel 9 Pro XL reports, and what the policy chose:

```
RAW stream 4080x3072 30.0fps stall=0.0ms      <- chosen
RAW stream 4080x2288 60.0fps stall=0.0ms
RAW stream 2032x1536 60.0fps stall=0.0ms
RAW stream 2016x1136 60.0fps stall=0.0ms
ZSL decision: ZSL raw stream: 4080x3072 30.0fps stall=0.0ms
```

### The pool

`RingBuffer.h` / `RingBuffer.cpp`. 32 slots of 4080x3072x16-bit is **765 MB**,
against a 256 MB Dalvik cap — it could not exist on the managed heap at all.

Backed by `mmap` rather than `std::vector`: a vector value-initialises, which
would commit every page of an 800 MB pool up front. `AHardwareBuffer` would be
the choice if the merge ran on the GPU, since it imports into Vulkan without a
copy; the merge is CPU-side today, so a hardware buffer would only add a
lock/unlock per frame. That trade changes in Phase 7, not here.

Slots are FREE / WRITING / READY / LOCKED. A push reserves a slot under the
mutex, copies **outside** it, then publishes — so a shutter press never waits on
a frame copy and a frame copy never waits on the merge. Locking hands out direct
`ByteBuffer`s over the pool's own memory, which feed the existing native merge
unchanged: the ring added no new merge code at all.

### Three things the tests changed

**Unlock frees rather than releases.** Returning merged frames to the ready pool
meant the next shutter press could pick them up again — frames seconds old
alongside fresh ones, quietly reintroducing the exact problem this phase exists
to remove. A snapshot now consumes what it locked.

**The pool is prefaulted at creation.** Lazy commit was one of the two reasons
for choosing mmap, and it was the wrong call here: a ZSL ring fills completely
within its first second, so every page faults anyway — just spread across the
frames that can least afford it. Cold, a 25 MB slot took 35 ms to fill against
2.4 ms warm, which is a dropped frame at 30 fps. Paying it once during session
setup (360 ms for 765 MB) removed the spike.

**A physical-RAM ceiling.** Linux overcommits, so a mapping far larger than the
machine has succeeds and only fails when the pages are touched — by which point
the OOM killer takes the process. Combined with prefaulting that turned a
harmless test into a fatal one. The pool now refuses anything above half of
physical RAM, and the caller falls back.

### A parallel copy made it worse

Splitting the 25 MB copy across four threads was tried and reverted:

| | mean | worst case |
|---|---|---|
| single threaded | 8.2 ms | **18.1 ms** |
| four threads | 7.1 ms | **30.7 ms** |

Creating threads on a path that wakes 30 times a second pays scheduler latency
every time. Dropped frames are decided by the worst case, not the average.

### Measured on device

13 instrumentation tests against the real pool, three consecutive runs:

```
100 shutter triggers, producer streaming concurrently
  handover      mean 127-224us      max 411us / 6.5ms / 6.0ms   (budget 10 ms)
  frames pushed 1202-1211           dropped 0 in every run

sustained 30 fps, full resolution
  pushed 90/90   dropped 0   gaps 0   measured 30.0 fps
  copy          mean 9.5-10.2 ms    max 15.8-28.2 ms  (interval 33.3 ms)

200 full-resolution frames = 5 GB of pixels
  Java heap growth: 0 KB
```

The handover is bounded by a mutex and a scan of at most 32 entries, not by
frame size: locking a 25 MB frame and a 0.6 MB frame both measure 3 us.

**The burst window: 8 frames now span 233 ms instead of 5.6 s — 24x tighter.**

Release JNI linkage verified rather than assumed: `RawRing` and `NativeMerge`
map to themselves in `mapping.txt` and the native method names survive in the
release dex.

### What the live run found

Running the stream on hardware immediately found a bug no test had, and
resolved the open design question.

**The stream opened twice.** A `SurfaceView` is recreated when its fixed size is
applied, so `onSurface` fired a second time with a *different* Surface. That
relaunched the effect while the first `open()` was still running inside
`NonCancellable`, giving two Camera2 sessions and two 239 MB pools 5 ms apart —
and the second open closed the first device out from under it:

```
ring: 10 slots of 4080x3072, 239.1 MB reserved
ring: 10 slots of 4080x3072, 239.1 MB reserved     <- second pool, leaked
W ZSL repeating request update failed
  java.lang.IllegalStateException: CameraDevice was already closed
```

"A surface exists" turned out not to be the same question as "the session is
targeting the live one". Opening is now serialised behind a mutex, and the
Surface the session was built against is tracked by identity, so a replaced
surface closes and rebuilds rather than racing. After the fix:

```
ring: 13 slots of 4080x3072, 310.8 MB reserved
pool prefaulted 310.8 MB in 127 ms
ZSL raw stream: 4080x3072 30.0fps stall=0.0ms, ring 13 slots, burst up to 11
```

One pool, one session, no errors.

**The viewfinder renders, correctly oriented.** This was the open question —
whether a SurfaceView behind the window would be painted over by the Compose
background. It is not: the preview is live, upright, and the chips and shutter
draw on top of it. The reasoning behind choosing SurfaceView over TextureView
held up: Camera2 tagged the preview buffers with the sensor-to-display rotation
and SurfaceFlinger honoured it, with no transform matrix anywhere in the app.

**The ring is smaller than the bench figure, and that is correct.** The device
had ~1.4 GB free of 15.2 GB, so the budget allowed 13 slots rather than 32. A
flat four-slot write headroom would have taken a third of that ring, so headroom
now scales with depth (`capacity / 8`, clamped to 2..4) — 13 slots gives a burst
of 11 instead of 9.

### Verified on the live camera

Phase 6 shipped with its central claim untested: everything had been checked
against synthetic frames pushed into the ring by hand, which proves the pool
works and says nothing about whether the sensor delivers raw at thirty frames a
second into it.

It does.

```
live stream: pushed=89 dropped=0 heldOff=0 gaps=0 fps=29.7 maxInterval=33.6ms
             lost=0 failed=0
handover:    8 frames spanning 235 ms
shutter:     raw merge -> DNG + JPEG, 6 frames
             handover 59us, merge 969ms, develop 884ms, write 162ms
```

Eighty-nine full-resolution raw frames in three seconds, nothing dropped, no
gaps in the sensor timestamps, no buffers lost. The design predicted a 233 ms
burst window; the camera delivered 235. **The shutter press itself costs 59
microseconds** against a ten millisecond budget — and the stream kept running at
29.8 fps with nothing dropped *during* the capture, which is the other half of
the claim.

Two things made this testable at all. The preview target is an `ImageReader`
rather than a display surface — the camera does not care what consumes the
preview stream, and a locked phone has no screen to give. And the CAMERA
permission comes from `GrantPermissionRule` inside the test, because the harness
uninstalls the app between runs and takes any shell grant with it.


---

## Phases 7-9 — the picture itself

Phase 6 fixed how frames are *captured*. This block is about what happens to
them afterwards, and it started with a correction from the brief: **not** a
zero-processed look. Adobe's own position is that users still want a natural
tone curve, and they are right. What is worth avoiding is the *phone* look, not
processing as such.

### The demosaic was throwing away most of the detail

The old demosaic averaged every sample of a colour in a 3x3 neighbourhood,
*including the pixel's own*. A green site sits at the centre of five greens, so
the green the sensor actually measured there was blurred with four others before
it reached the image. Half the pixels on a Bayer sensor are green. Most of the
luminance detail in every photograph was being destroyed at the last step, after
a burst merge had gone to considerable trouble to capture it cleanly.

Replaced with Malvar-He-Cutler gradient-corrected interpolation. Measured
against the full-colour image the CFA was sampled from:

```
RMSE vs ground truth        0.03351 -> 0.02294   1.46x better
colour error on a grey edge 0.2403  -> 0.1126    2.1x less fringing
two-pixel detail retained   0.2854  -> 0.5271    1.85x more contrast
```

The last number is the one that matters: against a true modulation of 0.50, the
old gather was destroying **43% of fine detail**.

### There was no rendering curve at all

The render was linear to 0.70 then an exponential shoulder. No toe, no midtone
contrast, no highlight behaviour beyond compression. That is not neutrality, it
is a choice, and it is the wrong one — it produces a technically faithful image
with no shadow density, no midtone separation, and highlights that go grey
rather than glowing.

Now three stages, in the order a photographic process applies them: a
hue-preserving roll-off in linear light, highlight desaturation driven by the
**scene-linear** peak, and an S-curve after the gamma encode.

The middle one took two attempts and is the interesting one. Driving
desaturation from the compressed value cannot work: the roll-off pushes
everything bright asymptotically toward 1, so afterwards a blue sky and the sun
are indistinguishable. Measured before compression, a sky just under white keeps
its blue and a specular several stops over goes white.

Everything is global. No pixel's rendering depends on its neighbours, which is
structurally why this cannot halo — the difference from local tone mapping.

### All five lenses

The app could only use the main camera. Finding the others took two passes: the
camera id list reports two cameras on this phone, because the ultra-wide and
both telephotos are *physical sub-cameras* of one logical camera, reachable only
through `getPhysicalCameraIds` and openable only by opening the parent and
tagging each `OutputConfiguration` with the physical id.

That found ten entries, with the main camera advertised three times at focal
lengths a millimetre apart. Clustering by ratio rather than equality collapses
them — two entries within 12% are the same glass, and real optics are separated
by factors of two:

```
12mm 0.5x, 24mm 1x, 49mm 2x, 110mm 4.6x, 220mm 9.2x    all RAW-capable
```

Labelled in **millimetres first**. "24mm" and "110mm" describe what the frame
will look like; "1x" and "5x" only describe a ratio between two parts of this
particular phone.

### Expose for the highlights, let the merge pay for the shadows

A clipped highlight is gone — the sensor recorded its maximum for every
brightness above the limit. A noisy shadow still contains its detail, merely
buried, and averaging digs it out.

A burst camera can exploit that asymmetry by a precisely knowable amount:
averaging N frames improves SNR by sqrt(N), which is `0.5 * log2(N)` stops of
exposure that can be given up while arriving at the same shadow noise. **Eight
frames buys 1.5 stops of highlight headroom for nothing; thirty-two buys 2.5.**
That is the entire argument for burst capture as one number, which is why frame
count now feeds the exposure decision.

It runs continuously rather than at the shutter, because in a zero-shutter-lag
camera the frames already exist when the button is pressed. That needed a way to
measure without consuming, so the ring can now histogram its newest frame in
place under its own lock.

### Lens shading

Raw is *defined* as uncorrected, so the raw-first pipeline had inherited a stop
and a half of corner falloff that the camera's own JPEG path removes — and it is
not neutral, so corners were a different colour as well as darker. Now corrected
from the map the camera reports per capture, bilinearly interpolated because the
grid is 240 pixels per cell and sampling it flat would step across a clear sky.

One piece of wiring that is silent when wrong: the map is only reported if the
request asks for it.

### Tap to focus, pinch to zoom

Absent, and more conspicuous than any of the computational work. The coordinate
mapping is the careful part, because getting it wrong does not crash — it
focuses somewhere else, which reads as unreliable autofocus.

Made pure Kotlin on a `SensorRect` rather than `android.graphics.Rect`, which is
what makes it testable at all: the framework Rect is a stub in unit tests whose
every method returns zero, so tests written against it compare zeroes and pass
while proving nothing. Two tests failed exactly that way and were the reason for
the refactor.

### The rest of the camera

Things every serious camera has and this had none of, added in a block:

- **Tap to focus and pinch to zoom.** The app could not be pointed at a subject
  that was not in the middle of the frame.
- **A level**, tested against known gravity vectors because a level that reads
  backwards is worse than none. Roll is suppressed past seventy degrees of tilt,
  where gravity lies along the viewing axis and roll is genuinely undefined.
- **A live histogram**, square-root scaled so a few hundred clipped pixels show
  against hundreds of thousands of midtones, read in place from the ring so it
  consumes no frames.
- **Settings that persist**, reconciled against real capabilities on the way in,
  because sending manual exposure to a camera without MANUAL_SENSOR has the
  whole request rejected and takes every other setting with it.
- **Capture sharpening.** None was applied at all, which is soft rather than
  neutral: even a perfect demosaic delivers less acutance than the lens
  projected. Safe here in a way it is not on a single frame, because the merge
  has already reduced the noise it would otherwise amplify.
- **The merge now reports the noise it measured.** `estimatedSigmaAtMid` had
  been returning zero since the native rewrite, so every claim about merge
  quality rested on a number that was not being computed. Verified against known
  noise: 2.10 for amplitude 4, 20.97 for amplitude 40.

### Colour from the sensor rather than from the ISP

`COLOR_CORRECTION_TRANSFORM` is the matrix the camera's own processor chose,
tuned to produce the manufacturer's rendering. Using it means inheriting the
look the stock app ships.

The sensor also carries the colorimetric data a DNG carries: colour matrices
under two illuminants, calibration transforms, and forward matrices taking
white-balanced camera space to XYZ. Rendering through that describes what the
sensor sees rather than what the vendor wants it to look like.

```
neutral 0.5 renders as (0.5000, 0.5000, 0.4998)
```

with all three matrix rows summing to 1.000, which is the property that makes it
true. Two bugs on the way: the forward matrix has to map the scene's neutral
onto the **D50 white point** and not onto (1,1,1) — normalising to the wrong one
rendered every grey visibly blue — and the illuminant blend ran backwards.

A wrong conclusion is worth recording. A probe reported all six calibration keys
as absent on this device, and a whole fallback path was written on the strength
of it — reading the tags back out of a DNG that `DngCreator` had written. The
keys are not absent: **Android withholds them from an app that does not hold the
CAMERA permission**, because a sensor's calibration is close to a fingerprint.
The fallback is still worth having for hardware that genuinely stays silent, but
it is not the main path, and it was built on a misreading.

### An optimisation that did not work

The merge's inner loop does two divisions per pixel purely to find which
alignment tile a pixel belongs to — twelve million times per frame, for an
answer that depends only on the coordinate. Precomputing it per column looked
like an obvious win and made no measurable difference: 1051 ± 60 ms against
roughly 1033 ms before, which is the same number. Reverted, and recorded,
because the next person to look at that loop will have the same idea. The merge
moves 25 MB of frame and touches 100 MB of float accumulators per frame; that is
where its time goes, not in the arithmetic.

Develop, by contrast, really was doing redundant work: the black-level
subtraction, shading and white balance were happening inside a thirteen-neighbour
read, so all of it ran thirteen times over through a coordinate-clamping lambda.
Normalising once into a float plane took it from **1899 ms to 910 ms**.

### Focus peaking, and a metric that was wrong in an interesting way

Manual focus on a phone is guesswork: the screen is small and bright and shows a
preview that has already been sharpened. Peaking replaces judgement with a
reading, taken from the sensor's own image in the raw ring rather than from the
preview — peaking the preview would measure the ISP's sharpening rather than the
focus.

Contrast is measured *relative to local brightness*. Raw gradient scales with
brightness, so peaking on it lights up every highlight and ignores the shadows,
which is exactly backwards when focusing on something dark.

The focus metric took two attempts. Counting pixels marked above a threshold
reports a **defocused** frame as sharper, because blur spreads an edge across
more pixels rather than removing it: a three-times-blurred pattern scored 0.952
against 0.466 for the sharp original. Summing the gradient fails too, since
blurring a step into a ramp leaves its total variation unchanged. Mean *squared*
gradient works, because squaring rewards concentration — which is what focus is:

```
defocus series: 0.354, 0.151, 0.094, 0.061, 0.041
```

### Handing memory back

The ring is up to 765 MB, more than most applications use in total. Android does
not warn twice about holding it under pressure — it kills the process, and the
user loses the viewfinder rather than losing zero shutter lag. The app now
releases the ring at `TRIM_MEMORY_RUNNING_LOW` and degrades to sequential
capture, which still takes photographs. `RUNNING_MODERATE` is deliberately
ignored: it fires routinely, and responding would surrender the ring during
ordinary use.

### UI testing, and a claim that had to be withdrawn

A batch of Compose tests appeared to prove the overlays rendered. They did not:
the test rule launches an activity, an activity behind the keyguard never
resumes, and `setContent` produced no hierarchy at all. The tests only rendered
and never queried, so nothing noticed — a UI test that asserts nothing is
indistinguishable from one that works.

Found by writing a test that *did* query, then confirmed by a two-line check
proving even `Text("hello")` was absent. Every render path now calls
`assumeRendered()`, so on a locked device the thirteen UI tests report as
skipped rather than as passing.

### Defective sensor sites, which the merge cannot touch

Every sensor has sites that read wrong, and on a fifty-megapixel sensor there
are hundreds. They are not random: a site is wrong the same way in every frame,
which is exactly why burst merging does not help — averaging suppresses what
varies, and a constant defect survives untouched. Cleaning up the surrounding
noise only makes the dots more obvious. **The better the merge, the worse they
look.**

Comparison is against the four sites two pixels away, the nearest of the same
colour, and a site is replaced only when it lies outside the range of *all four*
by a margin. That deliberately leaves some defects: two defects two sites apart
shield each other. It is the same property that lets a real two-pixel highlight
survive, and one cannot be had without the other — taking a star out of an
astrophotograph would be the worse failure.

### Capture modes

The machinery all existed; what was missing was a way to say what you are
photographing, because the right answers are contradictory.

**Night** takes every frame the ring has, allows a quarter-second exposure, and
switches the highlight guard *off* — a dark scene has no highlights worth
protecting, and pulling exposure would spend the shadow detail the mode exists
to gather. 2.40 stops of recovery over a 933 ms burst.

**Action** takes four frames, and that is the interesting decision. More frames
is not simply better: the merge's robustness weighting rejects frames where the
subject has moved, so extra frames contribute nothing while lengthening the
window and making the rejection worse. 1.00 stop over 133 ms.

**Auto** chooses between ordinary and night and *never* chooses action. Whether
something is moving is not visible in a brightness histogram; guessing would be
wrong as often as right and wrong in the expensive direction. Motion is
something the user knows and the camera does not.

### A thumbnail that does not read your photo library

The obvious implementation asks MediaStore for the newest image in the app's
folder. On device that returns nothing — an app only sees entries it owns, and
ownership is lost on reinstall. Making it work would mean holding
`READ_MEDIA_IMAGES`, permission to read the user's entire photo library, to show
a thumbnail of a picture the app had just taken itself.

Recording the URI at the moment of writing needs no permission, always shows the
right picture, and costs one string.

---

## Super-resolution by telephoto mosaic

The differentiating feature, and the only one on the roadmap that produces a
result a phone camera cannot rather than a better version of one it can. Full
design in [SUPERRES.md](SUPERRES.md).

Sweeping the 110mm across the 24mm framing gives that framing at **263 MP** from
7x7 tiles — twenty times the main camera's pixel count, with no invented detail.

It rests on a fact that is exact rather than approximate: for a camera rotating
about its optical centre, the mapping between two views is a homography with no
residual, *whatever the scene geometry*. The burst aligner cannot do this — it
estimates translation, and rotation makes parallel lines converge.

**Built and tested:** projective registration with Hartley normalisation, RANSAC
(one wild match drags a least-squares fit 41 px; RANSAC is exact on the same
data), Shi-Tomasi corner detection and matching that survives an exposure change
mid-sweep, Brown-Lowe match verification, capture planning, and a native
compositing canvas whose feathered seam measures a 1/255 step across a
deliberate 40-code brightness difference.

**Not built:** guided capture UI, locking exposure across the sweep, tiled output
for very large canvases.

**The limitation, which is honest and not fixable:** hand-held panning rotates
about the wrist rather than the lens, translating the camera a few centimetres.
Distant subjects do not care; near ones do, and no homography can fix it because
objects at different distances moving differently is depth information. Works
for landscape, architecture and flat subjects; does not work for close subjects
with depth.

---

## Mistakes made and corrected, second block

**A verification rule that punished good scenes.** Match verification used a
fixed inlier ratio, which is the intuitive choice and is wrong: it gets *harder*
to satisfy the more features a scene offers. A detailed frame produced 182
candidate matches of which 61 agreed on the correct transform — overwhelming
evidence that a 45% ratio test rejected as "a third".

**Fractional weights in an integer field.** The mosaic blend weight is 0..1 and
was stored in a `uint16`, so every partial contribution truncated to zero and
the entire feathered border of every tile rendered as uncovered. The composite
had a 36-pixel transparent gap down the middle of the overlap. Only the seam
test found it.

**A parallel copy that made things worse.** Splitting the ring's 25 MB frame copy
across four threads improved the mean from 8.2 ms to 7.1 ms and the *worst case*
from 18.1 ms to 30.7 ms, because creating threads on a path that wakes 30 times
a second pays scheduler latency every time. Dropped frames are decided by the
worst case. Reverted.

**A test that measured the wrong thing.** The native tone curve check measured
absolute slope from sensor code to output byte, which is dominated by the sRGB
encode being steepest near black by construction. It reported shadow slope 1.9
against midtone 1.3 and looked like a broken S-curve. Comparing against the same
render with contrast disabled isolates the actual curve.

**Claiming a tangent ratio differs from a focal ratio.** A mosaic test asserted
the canvas gain was *not* the focal length ratio. It is exactly that, since
tan(hfov/2) is 18/f by construction. The real naive error is using the ratio of
*angles*, which undersizes by 14%.

**A ratio test that kept its worst matches.** The degenerate branch was
backwards: guarding with "only apply the ratio when the runner-up is imperfect"
meant that when the runner-up was *exactly* as good as the winner -- a feature
that is not identifiable at all -- the test was skipped and the match kept. A
perfectly periodic pattern registered to a confident (-87, -57), not even a
multiple of its own 16-pixel period.

**Corner detection on a stride.** Scanning every second pixel quantised detected
positions to even coordinates, so any displacement with an odd component put the
corner a pixel off in one frame and not the other. A ten-by-five shift matched
145 points of which 8 agreed. Real displacements are arbitrary, so this would
have made stitching work only sometimes.

**Gravity pointing the wrong way.** The level's first tests passed a vector up
the screen instead of down. Two failed immediately, which is what tests against
known vectors are for.

**A sigma test that measured nothing.** The noise figure was checked on a 64x48
frame, where the estimator -- which samples every eighth pixel and bins by
brightness -- had about three samples per bin and fell back to its floor. It
reported the same number for a quiet burst and a violently noisy one while
passing an assertion that it was greater than zero.

## Next step

**Finish the mosaic.** Registration, planning and compositing are done and
tested; what remains is orchestration rather than algorithm — guided capture
with a coverage grid, locking exposure and white balance across the sweep, and
tiled output for canvases too large to render in one piece.

**Then Vulkan.** The merge is the bottleneck at 1884 ms, and this is where
`AHardwareBuffer` finally earns its place over mmap: it imports into Vulkan
without a copy, which mmap'd pages cannot.

Still outstanding from Phase 6: the shutter has never been pressed on a live ZSL
stream, so the handover, merge and DNG output from ring frames are untested on
hardware.

## Outstanding for release

- [ ] Privacy policy: fill in effective date, developer name, contact; host at a
      public HTTPS URL (not a PDF)
- [ ] Create and back up a real release keystore — losing it means never being
      able to update the listing
- [ ] Complete the Play Data safety form (required even though nothing is
      collected)
- [ ] Trademark search on "Multiframe" — the name is descriptive, so weak as a
      trademark, and *MultiFrames* (plural) exists on Play in another category
- [ ] Store screenshots, content rating, developer verification
