# Multiframe — build log

A record of the session that built this app, written 2026-08-24.

**Multiframe** is an Android computational photography camera. Every shutter
press captures a burst of frames, aligns them to cancel handshake, and merges
them to reduce sensor noise. Inspired by the general technique Adobe's Project
Indigo demonstrates on iPhone; original code, name, icon and UI throughout.

- Package: `dev.multiframe.camera` (final — cannot change after publication)
- Target device for development: Pixel 9 Pro XL (`komodo`), Android 17 / API 37
- ~21,000 lines across 95 Kotlin files and 6 native files
- 332 JVM unit tests and 99 on-device tests

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
- **The merge was the bottleneck at 1884 ms.** Phase 7 took it to 934 ms for a
  six-frame burst by moving alignment to C++; develop, at around 900 ms, is now
  the largest single term in a capture.
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

### What can and cannot be measured here

Two attempts to speed up the merge were reverted, and a third measurement
undermined the reasoning behind both.

Repeating the *same* build three times, after letting the device settle, gives
merge times of 1008, 1131 and 1008 ms and develop times of 949, 913 and 1027 ms.
That is a **12% spread on identical code**. The phone has been running tests
continuously for hours, and its thermal state drifts.

So the two optimisations that were reverted — precomputing the tile index per
column, and interleaving the sum and weight accumulators — produced differences
of 2% and 11% respectively, both of which sit inside that spread. The earlier
commit message claiming the first "made no measurable difference" was right by
accident; the claim that the second "made it worse" was over-confident and is
withdrawn here. Neither could be resolved.

Reverting both was still correct — complexity with no demonstrated benefit is
not worth keeping — but the stated reasons were firmer than the evidence.

**The practical rule: changes below about 15% cannot be judged on this device in
this state.** The develop rewrite (1899 → 910 ms) is far outside that and is
real. Anything claiming a 10% gain in the merge should be treated as unproven
until it can be measured on a cool device, and the honest route to a faster
merge is a different execution model rather than tuning this loop.

### Two real speedups, found by looking for structural mistakes

After two failed micro-optimisations and a measurement that showed the device
could not resolve anything under 15%, the useful question changed from "what can
be shaved off this loop" to "what is structurally wrong".

**The merge ran at the speed of the slowest core.** The band scheduler divided
the image into one equal band per thread and joined. That is right for a
symmetric machine and wrong for a phone: a Pixel has a few fast cores and
several slow ones, so the pass could not finish until the slowest core ground
through its share while the fast cores sat idle. Six bands per thread, claimed
dynamically:

```
merge before: 1008, 1131, 1008 ms
merge after:   812,  832,  904 ms
```

The ranges do not overlap, which is the standard this project now holds
performance claims to.

That change would also have introduced a race — the merge accumulated its
statistics into 64 fixed slots indexed by an incrementing counter, safe only
while each thread ran exactly one band — so the slots were replaced with a lock
taken once per band.

**Develop re-faulted 150 MB on every shot.** It allocated a normalised plane, a
luma plane and an output copy per capture. Measuring this took two attempts and
the first said the opposite of the truth: comparing separate test runs showed
nothing, because every run starts a fresh process with a cold pool. The effect
only appears across shots within one session:

```
buffers freed between shots:  925, 1259,  959 ms
buffers kept:                 849,  826,  840 ms
```

The consistency matters as much as the speed — a camera whose shutter sometimes
takes half a second longer for no visible reason is worse to use than one
uniformly a little slower. The spread went from 334 ms to 23.

A full shot is now about 1.9 s against 2.4 s.

### The mosaic stopped chaining

A sweep gives each frame several independent measurements: the tile above and
the tile beside it both overlap it. Chaining used one and discarded the rest,
which is how a long sweep drifts while every individual join looks perfect.
Frames now register against every overlapping neighbour and are placed at the
consensus of all of them.

```
chained row residual      4.00 -> 2.15 px
two-dimensional grid      3.60 -> 0.64 px
loop closure, truth 1300: chained 1312.9, with closure 1303.5
```

On a raster sweep the tile below the starting one now lands at exactly one step
down and none across, however far the row travelled in between.

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

**Not built:** guided capture UI. Exposure, white balance and focus locking
turned out to be built already -- `lockForSweep` pins all three, `MosaicCapture`
calls it, and a device test asserts the stream is handed back afterwards. The
status table had simply never been updated.

**The limitation, which is honest and not fixable:** hand-held panning rotates
about the wrist rather than the lens, translating the camera a few centimetres.
Distant subjects do not care; near ones do, and no homography can fix it because
objects at different distances moving differently is depth information. Works
for landscape, architecture and flat subjects; does not work for close subjects
with depth.

---

## Metadata: what the photograph says about itself

Every JPEG this app had written until now carried none. `Bitmap.compress`
writes no metadata at all, and the proof files show it: JFIF, an ICC profile,
and no APP1 segment anywhere in the file. No camera, no lens, no exposure, no
date beyond the filesystem's. For an app whose argument is that it produces a
photographer's result, that is the first thing anyone would look for and not
find.

`ExifWriter` puts make, model, software, date, exposure, ISO, aperture, focal
length and 35mm equivalent onto the saved file, taken from the same
`TotalCaptureResult` the DNG's metadata comes from. Every field is optional,
because every one of them is optional in Camera2: a device that reports no
aperture gets a photograph with no aperture tag rather than a plausible wrong
one. The DNG needs none of this -- `DngCreator` wrote it already.

**The tags want inconsistent forms, and say nothing when they are given the
wrong one.** Found by probing a real file rather than by reasoning about the
documentation, which says nothing about it:

| Tag | Kept | Discarded |
|---|---|---|
| `TAG_EXPOSURE_TIME` | `"0.004"` | `"4/1000"` |
| `TAG_F_NUMBER` | `"1.7"` | `"170/100"` |
| `TAG_FOCAL_LENGTH` | `"690/100"` | `"6.9"` |

Two of the three want a decimal and the third wants a rational; a value in the
other form is dropped silently rather than rejected. `ExifRoundTripTest` pins
all three, so an Android release that makes them consistent fails a test instead
of quietly emptying the tags again.

**Written before the file is published, not after.** MediaStore scans a pending
item at the moment its pending flag clears and fills its own columns -- date
taken, orientation, exposure -- from what the file holds then. Tags added after
that are in the file and missing from the columns a gallery actually reads,
which looks from the outside exactly like not writing them. So the writing
happens inside `ImageSaver`, between the compress and the publish, rather than
at the call site where it started.

**The orientation tag has to say "normal".** The pixels are rotated upright
before the file is written, so recording the device's rotation here as well
would turn the picture a second time in every viewer that honours the tag.

**The description carries the frame count.** "8 frames merged" is the one thing
about these files that no other tag records: a merged burst and a single frame
at the same settings are otherwise indistinguishable in a library.

The 35mm equivalent comes from the lens catalogue rather than the capture
result, which reports only the physical focal length -- so both raw paths, ZSL
and sequential, now carry the lens that took the picture down to the writer.

**Confirmed on Android 16, then on Android 17 hardware.** The table above is no
longer an inherited claim. The probe held on API 36 in an emulator, and then the
whole path was exercised on the Pixel 9 Pro XL on Android 17 -- a newer OS than
any measurement in this log had been taken on.

The proof is a photograph rather than an assertion. Read back off a file the
phone produced through a real shutter press:

    ImageDescription       Multiframe: 4 frames merged, 24mm, 1/30, ISO 1410
    Make / Model           Google / Pixel 9 Pro XL
    Software               Multiframe
    Orientation            1
    DateTimeOriginal       2026:08:25 21:54:13
    ExposureTime           0.0335          (1/30)
    ISOSpeedRatings        1410
    FNumber                1.68
    FocalLength            6.9
    FocalLengthIn35mmFilm  24

`FocalLength` is the one that mattered. The app writes it as `690/100`, and the
device log confirms it reads back as `focal=690/100` -- the rational form
surviving exactly as the format table predicted. Written as a decimal it would
have been dropped in silence, and every photograph would have carried no focal
length at all.

---

## The mosaic could not save its own result

The sweep worked and the file did not. `MosaicSession.save` rendered the canvas
into a `Bitmap` of the same dimensions and handed that to the saver, which at
the planner's default 80 MP cap is a 320 MB allocation on top of the 610 MB the
canvas already holds, on a phone with 1.4 GB free. The code knew: it carried an
`OutOfMemoryError` branch whose comment said tiled writing was the answer and
was not built yet, and whose behaviour was to return no photograph at all.

The fix is to stop producing a second copy. `AndroidBitmap_compress` -- in the
NDK since API 30, against a minSdk of 33 -- takes a plain pixel pointer and a
write callback rather than a `Bitmap`, so it can compress from memory the app
already owns and hand back the JPEG in pieces to be written straight down a
descriptor.

That leaves the format difference: the canvas is eight bytes a pixel (16-bit
RGB plus an accumulated weight) and the compressor wants four (packed RGBA). So
the accumulator is collapsed **in place**. The output is half the width of its
input, so the write pointer always trails the read pointer within the same
mapping, and the whole pixel is read into locals before anything is written --
which matters at exactly one address, the origin of an uncropped canvas, where
the read and the write are the same four bytes.

Peak cost of saving an 80 MP mosaic: 610 MB before, 610 MB after. The 320 MB
copy is simply gone, and so is the branch that gave up.

Measured on the phone, saving a sweep from a real canvas:

    flattened 1877x1412 from 5645x4250 canvas, 10 MB in place
    wrote 1877x1412 (2.7 MP) at quality 95

The canvas was 24 megapixels -- 192 MB of accumulator -- and the write allocated
nothing. The old path would have asked for a 24 MP `Bitmap`, 96 MB, on top of
it. The file that came off the device carries `Multiframe: 1 tile stitched,
24mm`, with no exposure tag, which is what a mosaic is supposed to say.

**It does not raise the ceiling, and saying otherwise would be the easy lie.**
263 MP is out of reach because *compositing* it needs a 2 GB canvas, not because
of how it was written. What this changes is that the result the phone can hold
is now one it can also save.

**The canvas is consumed by saving.** Collapsing in place destroys the weights,
so coverage, compositing and rendering all have to refuse afterwards rather than
read pixels back as though they were still accumulator values. That is honest
for this pipeline -- `save` is called once, at the end of the sweep, and the
session is closed immediately after -- and there is a test that every one of
those operations says no.

**The output is cropped to what was actually covered.** A hand-held sweep does
not cover a rectangle. Uncovered pixels were already transparent so a caller
could crop, but nobody was cropping, and JPEG has no alpha -- so the result
would have been framed in black. The write now finds the covered bounding box
first, and an empty canvas produces no file rather than a black one, on the
grounds that a black photograph looks like one that failed silently.

**What the file says it is.** A mosaic's tiles are not merged frames -- the
opposite trade, resolution across a scene rather than signal-to-noise at one
framing -- so the description says "49 tiles stitched" and the lens recorded is
the *target* framing rather than the telephoto that swept it: the photograph has
a 24mm field of view, whatever took it. Exposure and ISO are omitted entirely,
because the tiles were shot under whatever the meter decided at the time and
there is no single honest number. Locking exposure across the sweep is what
would earn those tags.

---

## An emulator, and what it is allowed to prove

The development phone has been locked throughout, and for a long stretch there
was no phone attached at all. An arm64 AVD closes most of that gap on an Apple
Silicon host: the app's own `arm64-v8a` build runs natively, and the screen is
unlocked, which is the one condition the Compose tests could never get.

**On the phone: 74 device tests, 0 failures, 0 skipped, in 1m52s.** Everything
in the suite now runs on the hardware it was written for, including the fourteen
Compose tests, because the screen was unlocked for the first time.

On the emulator beforehand: **74 tests, 0 skipped, 1 failure** -- and the failure
was the emulator rather than the app: `getCameraCharacteristics` for "unknown device 0", a camera
the framework advertises with nothing behind it. That test now returns null and
skips in that situation, the same answer it already gave for a camera it cannot
open.

Two results worth having:

**The fourteen Compose tests ran, and passed.** Every previous run reported them
skipped, because an activity behind a keyguard never resumes and `setContent`
produces nothing. The viewfinder controls, the guides, the level and the
histogram have now rendered and answered questions about their own hierarchy.
Nobody has still *watched* the app run -- that needs the phone -- but "compiles
and is covered in principle" has become "renders and behaves".

**The EXIF format asymmetry is confirmed**, which retires the one claim in this
log that rested on a measurement nobody could re-run.

What an emulator is **not** allowed to settle here, and the reason the numbers
in this log stay phone-measured: **anything timed.** The ring's 30 fps copy
budget, the merge at 812 ms, the develop, the 59 microsecond handover -- those
are claims about a particular phone. Measuring them on a virtual machine on a
Mac and writing them down would replace measurement with fiction, which is
precisely the failure this log exists to avoid. Nor does it cover the camera
path: there is no RAW-capable virtual camera, so those tests bail out by design.

---

## The interface, looked at for the first time

With the emulator up, the app could finally be *seen* rather than only tested,
and the verdict from the first look was that it read as a prototype. It did.
Everything in it was a function-first decision by something that had never been
viewed, which is exactly what the history would predict.

What was actually signalling it:

**Every control was the same chip.** Nine identical pills doing five different
jobs -- persistent modes, cycling values, panel toggles, one-shot actions and
navigation. That is the shape a UI takes when each capability got a chip as it
landed. Left alone for now: fixing it means restructuring, and this pass was
deliberately visual.

**The palette was nobody's choice.** The active state was Material's default
blue, over a photograph. A saturated hue in the corner of the eye changes how
the colours under it are judged, which is the one thing an app whose argument is
neutrality cannot afford. Replaced with a rule instead of a colour: **selection
is shown by weight, not by hue** -- an active control is filled bone, an
inactive one is a dark pane with a hairline -- which leaves exactly one accent,
spent only on numbers and live readings, where an instrument has always spent
it.

**Monospace was doing two jobs at once.** It said "instrument" and "unfinished"
simultaneously. Now labels are set in a UI face and mono is kept for the
numbers, which is the convention the About sheet had already arrived at on its
own.

**The controls floated on the photograph.** White chips over a bright sky are
unreadable, and a row of them on bare image reads as a debug overlay. A gradient
scrim at top and bottom gives them somewhere to sit without putting a bar across
the frame -- and it makes the chip that runs off the edge look deliberate, which
it is, because the row scrolls.

**The status bar sat inside the frame.** A clock and a battery meter are telling
you about the phone while you are trying to look at the picture. Hidden, and it
returns on a swipe.

**The vocabulary was internal.** `1us-300ms` is what the Camera2 key says;
`1/1000000-1/3` is what a photographer reads. `focus 0-20.0D` is exactly right
and answers nothing; `focus to 5 cm` answers "can I get close to this?". And the
white balance presets were abbreviated to `TUNG`, `FLUO`, `CLOUD` -- a layout
constraint deciding the vocabulary, in a row that scrolls and never needed it.

**One thing that looked like a defect and was not.** The shutter appeared to
have a green ring. It is the photograph, seen through the gap between the ring
and the disc, which is what a shutter button has always looked like.

Still unexplained, and probably not the app: on the emulator the preview does
not fill the frame and the uncovered area is white rather than black, despite
the window background, the theme and now the root composable all being black.
The likeliest reading is the emulated camera's surface, since a `SurfaceView` is
a hole punched through the window. It needs the phone to settle.

---

## Phase 7 — the merge, and what was actually slow

The plan of record said Vulkan: the merge is the bottleneck, and
`AHardwareBuffer` earns its place over mmap because it imports into a GPU
pipeline without a copy. Before writing any of it, the number got taken apart.

`mergeMillis` had been reporting one figure for two very different things.
Alignment runs on the JVM, on a luma proxy; accumulation runs in C++. Both sat
inside the same timer. Split, on a Pixel 9 Pro XL over five frames:

    proxy 71ms    pyramid 19ms    align 2858ms    accumulate 295ms

**Alignment was 83% of the merge. The native accumulation the GPU plan was
aimed at was 9%.** Moving the accumulate to Vulkan and succeeding perfectly
would have taken a 3427 ms merge to about 3130 ms.

The inner loop is a sum of absolute differences over 8-bit samples, which is the
one kernel ARM has a dedicated instruction family for -- `UABD` and `UABAL` do
sixteen bytes at a time, where the JVM was doing one. So the search moved to
C++, and the Kotlin stayed as the reference implementation, which is the
arrangement the develop stage has always used.

    align       2858ms -> 345ms      8.3x
    merge       3427ms ->  934ms     3.7x
    whole shot  4313ms -> 1966ms     2.2x

The ranges do not overlap and the change is far outside this device's
run-to-run spread, which is the bar a claim here has to clear. Alignment and
accumulation are now within 10 ms of each other, so the next honest target is
whichever grows first, not whichever was named first.

**Parity is exact, not approximate.** Both are integer searches over the same
costs, so anything short of an identical field means a decision changed
somewhere -- and a tolerance would hide exactly the tie-breaking and
edge-clamping mistakes that are the likely way to get this wrong. The test
covers a flat scene, where every candidate ties and only the iteration order
decides, and a negative shift, where reads clamp to the edge pixel. 3024 tiles,
zero differing.

**What this does not do:** it does not touch the develop stage, which at ~900 ms
is now the single largest term in a capture. And it does not make the GPU idea
wrong -- it makes it premature. `AHardwareBuffer` is worth revisiting when
something on the GPU is worth 300 ms.

---

## Phase 8, opened — and "develop" turns out not to be develop

The same first move as Phase 7: split the number before spending anything on
it. It found the same shape of problem twice in a row.

`developMillis` does not time the develop. It spans the colour profile, the
shading map, the native develop, a full twelve-megapixel **bitmap rotation**,
**JPEG encoding**, the EXIF write and the MediaStore publish. Everything from
raw buffer to a file in the gallery, reported under the name of one stage in the
middle of it.

The native stages, instrumented per pass:

    develop:  black 98ms   shading 77ms   demosaic+tone 519ms   total 695ms
    sharpen:  luma 51ms    sharpen 86ms                         total 138ms

833 ms of native work against a reported 1528 ms, so roughly 700 ms of what has
been called "develop" all along is rotation, JPEG and gallery I/O. **The
demosaic and tone pass is 62% of the actual develop**, and is the honest target
if the pass is worth optimising at all.

**Nothing is claimed from that 1528 ms figure.** The same run reported the DNG
write at 966 ms against 123 ms earlier for identical code -- an eight-fold swing
in pure file I/O -- with the battery at 25% and the device warm from a solid
stretch of burst capture and hundreds of test photographs left in the gallery
for MediaStore to scan. The per-pass proportions are believable because they are
ratios taken inside one pass of one run; the absolutes are not, and this log has
withdrawn claims for less.

**Where that leaves the phase.** The measurement stands: demosaic and tone is
the target inside develop, and the largest single cost in a capture may well not
be develop at all but the JPEG encode that has been hiding inside its timer.
Both want a cool phone and an empty gallery before a number goes in this log.

### One change that needed no stopwatch to justify

Reading that pass turned up something worth doing on correctness grounds alone.
`toByte(renderDisplay(encodeSrgb(v)))` is a pure function of a single float, and
`encodeSrgb` already quantises its input to one of 4096 indices. So the gamma
lookup, the black point, the S-curve, the scale and the round all collapse into
a single byte lookup on that same index.

It is **exact, not approximate**, which is the only reason it is worth doing
here: each entry is built by evaluating the identical functions at the identical
point the old path would have evaluated them at. The arithmetic has not changed,
only how often it is performed -- four thousand evaluations per capture instead
of thirty-seven million. The parity test agrees to the bit:

    native vs Kotlin develop: worst 0/255, mean 0.000

The table is built on the stack rather than in a global, because two develops on
different threads would otherwise write the same table while reading it.

**No speed claim is attached to it.** It removes work that provably was being
done, and how much that is worth on a cool phone is a measurement nobody has
taken yet.

---

## Mistakes made and corrected, second block

**A wide load one byte past the end of the world.** The native alignment search
crashed the instrumentation process with `SIGSEGV` on a *read*, at a
page-aligned address. The de-interleaving load used at the finest level reads
thirty-two bytes to gather sixteen even-indexed samples; the last byte is
discarded, but it is still read, and when the final sample of a plane is also
its final byte that read is one past the allocation. The luma proxy is 2040x1536
-- 3,133,440 bytes, **exactly 765 pages** -- so its buffer ends flush against a
page boundary and the overread lands on an unmapped one.

The parity test had passed. Its plane was 320x256, which leaves slack after it,
so the same illegal read sat harmlessly inside the same page. A test at a
convenient size proved the search was *correct* and said nothing about whether
it was *legal*, and the two are not the same property. There is now a parity
case at the real proxy dimensions, which is the size at which this can be
caught.

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

**The mosaic is finished.** Registration, planning, compositing, writing the
result out, locking exposure and white balance across the sweep, and now the
guide. Two of those were already built and listed here as outstanding, which is
its own small lesson about roadmaps: `lockForSweep` pins exposure, white balance
and focus and `MosaicCapture` has been calling it all along.

What was genuinely missing was the guide. The assembler has tracked coverage on
a 32x32 grid from the beginning, under a comment saying it was for drawing the
sweep guide, and nothing ever drew it — so a sweep could tell the photographer
how much was covered and nothing whatever about *where*, which is the one
question a guided capture exists to answer.

It is drawn as a map rather than as an overlay on the viewfinder, and that
distinction is the design. During a sweep the preview shows the telephoto,
which sees a small fraction of the canvas being built; cells drawn across the
frame would appear to say "this part of what you are looking at is covered",
which is not what they mean. Drawn small, at the canvas's own aspect ratio, they
say what they are: the finished picture, filling in. Three Compose tests cover
it, including the empty and ragged grids that would otherwise divide by zero or
index past a row and take the viewfinder down with them.

**What remains on the mosaic is a judgement, not a feature:** nobody has swept a
real scene with it yet. Every claim about stitching rests on synthetic frames
cut from a generated scene, which is the right way to test the algorithm and no
substitute for pointing it at a building.

**Phase 8 has its numbers, and they point somewhere else again.** Measured at
31.2 C, holding steady through the run, on a charged phone:

    develop: black 45ms, shading 64ms, demosaic+tone 149ms, total 260ms
    sharpen: luma 27ms, sharpen 62ms, total 89ms
    develop breakdown: native+setup 360ms, rotate 0ms, encode+save 284ms

So of the figure this log has been calling develop, the native render and
sharpen together are about 350 ms, the setup around 110 ms, and **the JPEG
encode and gallery publish 284 to 375 ms** -- as much as all the native
rendering, and two and a half times the demosaic that was named as the target.
Demosaic and tone is 149 ms of an 800 ms figure: under a fifth.

**The rotation is measured now, and it is not small.** Every capture test asked
for zero degrees, and `OrientationTracker` hands the bitmap straight back at
zero -- so the rotation had never run under test at all, while a phone held
upright is the ordinary case rather than the exception:

    flat:     native+setup 472ms, rotate   1ms, encode+save 350ms
    upright:  native+setup 281ms, rotate 232ms, encode+save 346ms
    sizes (4080, 3072) -> (3072, 4080)

**232 ms on every upright photograph, absent from every figure in this log.**
The swapped dimensions are worth having in their own right: they confirm the
pixels really are turned rather than merely tagged, which is what earns the
orientation tag of "normal" the file carries.

**An obvious fix that was not one.** `Bitmap.createBitmap` was being asked to
filter, and a quarter turn maps each pixel exactly onto another pixel, so there
is nothing between samples to interpolate. Turning filtering off measured
296 ms against the filtered 232 -- no better, and pointing the wrong way. Skia
evidently takes the same path for an axis-aligned rotation whichever flag it is
given, and the difference is this device's ordinary run-to-run spread. Reverted,
because a change with no evidence behind it is worse than no change.

**A blocked transpose took it to 69 ms.**

    flat:     native+setup 386ms, rotate  0ms, encode+save 348ms
    upright:  native+setup 289ms, rotate 69ms, encode+save 334ms

The reason a transpose is slow is not arithmetic, it is memory: walking the
source along its rows means walking the destination down its columns, so every
pixel written lands in a different cache line and a 50 MB destination evicts
itself continuously. Copying in 32x32 tiles fixes that -- a tile of the source
maps to a tile of the destination and both fit in L1 while it is copied. Reads
stay sequential within a row; writes stay inside thirty-two short runs instead
of scattering across the whole image.

232 ms to 69, which is about 163 ms off every upright photograph. At 1024x768,
where the allocation is the same on both sides and only the transpose differs,
it is 5.7 ms against the framework's 15.2.

**Pinned pixel for pixel, because dimensions prove almost nothing here.** A
transpose with its handedness reversed produces an image of exactly the right
shape and the wrong contents, and the capture test asserting that 4080x3072
becomes 3072x4080 would pass on a mirrored picture. `RotateParityTest` compares
against the framework's Matrix path pixel by pixel, on a pattern where every
pixel encodes its own coordinates so nothing can move unnoticed, at sizes that
are deliberately not multiples of the tile -- the partial tiles at the right and
bottom edges being where an off-by-one would hide in a strip a few pixels wide.
Seven cases, zero differing pixels.

**The alternative was not taken, and remains available.** Not rotating at all --
leaving the pixels in the sensor's orientation and writing an EXIF orientation
tag, as every camera does and as the DNG already carries -- would cost nothing
at all. It is not an optimisation but a change to what the file *is*, trading
the last 69 ms for a dependence on every viewer honouring the tag, which is
precisely what this app rotates in order not to need. Worth deciding on its
merits rather than for speed, and 69 ms is a much weaker reason to decide it
than 232 was.

**The disk was not the problem, and that was worth checking rather than
assuming.** With 42 GB free instead of 5.9, and the gallery emptied:

    saveJpeg: compress+write 227ms, publish 47ms
    saveJpeg: compress+write 264ms, publish 97ms
    saveJpeg: compress+write 238ms, publish 44ms

`encode+save` was 284-375 ms on a 98%-full disk and is 290-379 ms on an empty
one. Freeing 36 GB moved it not at all. The cost is the JPEG compression -- some
240 ms to encode twelve and a half megapixels at quality 95 -- against 44 to
97 ms for the gallery publish. So the largest single item inside develop is
neither the demosaic nor the disk: it is turning the finished bitmap into a
JPEG, and it is larger than the demosaic and the tone curve together.

**What the free disk did change was the spread.** Three shots went from
670/532/569 ms to 608/610/621 -- the same mean, and a spread of 13 ms where it
had been 138. This log has already argued, when develop's working buffers were
kept between captures, that a camera which sometimes takes half a second longer
for no visible reason is worse than one uniformly slower. A full disk was buying
exactly that unpredictability. Three samples either side is thin evidence for a
mean and reasonable evidence for a tenfold difference in spread.

**Where that points, and what it bought.** The JPEG encode is not obviously
wasteful -- 12.5 MP at 52 MP/s through libjpeg-turbo is about what it costs.
But it ran *after* the DNG had been written, and the two have nothing to say to
each other. Both only read the merged buffer, and the native develop reads it
through its base address rather than the buffer's position, so they can run at
once:

    outputs: dng 348ms alongside develop 597ms, 597ms wall
    outputs: dng 264ms alongside develop 618ms, 618ms wall
    outputs: dng 451ms alongside develop 675ms, 676ms wall

The wall time is the develop time, to the millisecond. The DNG write is
completely hidden. A capture that spent 653 ms developing and then 303 ms
writing now spends 638 ms doing both -- about 320 ms off every shot, and none
of it from making anything faster.

**Two things it had to be careful about.** The write gets its own `duplicate()`
of the buffer: `writeByteBuffer` advances the position it is given and the
Kotlin develop fallback rewinds the same buffer, so sharing one would have had
them fighting over a cursor while neither changed a pixel. And `writeDng` used
to record the DNG as the review thumbnail on the understanding that "the JPEG
written straight after overwrites this" -- true only while the two were
sequential. Overlapped, whichever finished last would have won, and a DNG
thumbnail is a blank square because nothing here can cheaply decode its preview.
The thumbnail is now decided once, after both are done.

**What it cost.** Develop itself got noisier: on the same three-shot test,
608/610/621 ms became 618/742/789, because the writer competes for the same
cores. Dropping the writer's thread priority a notch was tried and changed
nothing measurable -- 176 ms of spread against 171, means within noise -- so it
was taken out again rather than kept on faith. The end-to-end figure is still
the one that matters, and it fell by about a third.

**First-capture figures are not steady-state ones.** The first capture after an
install measured a 1441 ms merge against 934 ms warm, which briefly looked like
a regression. It is a cold path: retained develop buffers not yet allocated,
caches cold, and a governor that has not ramped on an idle phone. Three
consecutive captures settle to 130-156 ms of alignment where the warm phone gave
176-270. Cool and warmed-up are different conditions and this log should say
which it means.

**Vulkan is not next, and the reason is on the record.** It was next while the
merge was assumed to be dominated by accumulation. Accumulation is 335 ms.
`AHardwareBuffer` still imports into a GPU pipeline without a copy, and that
still matters -- when there is something on the GPU worth the crossing.

**Phase 6 is closed.** The shutter has now been pressed on a live ZSL stream on
the phone, which had been outstanding since Phase 6 was written. The stream came
up on the 24mm at 4080x3072, 30.0 fps with `stall=0.0ms`, a twelve-slot ring
holding 287 MB of native memory, and the handover took **141 microseconds for
four frames over a 101 ms span**. Both the DNG and the JPEG were written from
ring frames, and both are on the device.

**The handover is not one number, and quoting it as one was the mistake.**
Twelve samples across the day's runs, in microseconds:

    50  56  58  60  71  79  135  237  237  320  415  517

The README's 59 us is real and reproducible -- it sits in the fast cluster --
but it is the good end of a distribution with a tail an order of magnitude
longer. The slow samples are the first captures after a stream comes up.

This log has already argued, about the ring's frame copy, that a worst case
decides whether frames drop and a mean does not. The same applies here: what
decides whether the shutter feels instant is the 517, not the 50. These were
taken under a test harness rather than the app, so the tail may partly belong
to the harness -- but "59 microseconds" should not be repeated as though it
were the whole story.

## The merge had no parity test

Develop, sharpening, lens shading and alignment are each pinned to a Kotlin
reference. The accumulation at the centre of the app was not. The only thing
comparing the two implementations was a test of the *noise figure* they report,
which says nothing about the pixels they produce -- so the native merge could
have drifted from `BayerAccumulator` in any direction and nothing would have
noticed.

It has not drifted: worst difference of one code out of 65535, which is the
rounding step where two float sums are truncated to integers, and the noise
figures agree. But that was luck rather than design, and it is now a test.

Two things it had to get right to be worth anything. The scene is 320x240, not
the 64x48 the develop fixtures use: the noise estimator samples every eighth
pixel and bins by brightness, and at 64x48 it has about three samples a bin and
falls back to its floor -- this log has been caught by exactly that before, and
a parity test running against two floors agreeing proves nothing. And the tile
grid at that size is 5x3 rather than the degenerate 1x1, so the per-tile
displacement path is actually exercised.

**Run on the emulator, not the phone**, which had dropped off wireless debugging
and could not be brought back remotely. That is legitimate here for the reason
already recorded: this is arithmetic on synthetic frames, framework behaviour
rather than camera behaviour, and no timing is claimed from it.

## The merge accumulation, without the divisions

With the parity test finally watching it, the accumulation could be touched. It
was doing two float divisions per pixel, twelve and a half million pixels a
frame, for values that do not vary per pixel at all:

- the tile column, `(x / 2) / tileW` clamped, which depends only on x and is
  therefore the same for every row of every frame in the burst
- the noise bin, `(value / (white + 1)) * bins` clamped, which depends only on
  the reference value at that site

Both became tables built once per frame. This is memoisation rather than
optimisation: each entry is the identical expression evaluated at the identical
input, so there is no approximation to argue about, and the parity test cannot
be made to fail by it. Measured on the phone at around 31 C, three frames:

    accumulate before:  180, 148, 190 ms
    accumulate after:   139,  91, 106 ms

The ranges do not overlap -- the slowest sample after is faster than the fastest
before -- which is the bar this log requires before a speed claim is allowed to
stand. Roughly a third off, for arithmetic that was never needed. Alignment sat
unchanged at 138-167 ms beside it, which is the control: nothing about the run
was faster in general.

**What is left in there is the branch and the address arithmetic.** Within a
run of pixels sharing a tile the displacement is constant, so the bounds test
and the source row pointer could both be hoisted to the run rather than the
pixel, and only then is there any point reaching for NEON. That is the next
thing, not this one.

## The accumulation, run by run instead of pixel by pixel

The last entry left this as the next thing: within a run of pixels sharing a
tile the displacement is constant, so the bounds test and the source row pointer
could both be hoisted to the run rather than the pixel. That is now done, and it
is worth about a quarter of the accumulation -- but the measurement took four
attempts, and three of them would have given the wrong answer.

**What moved.** The alignment is per tile, so a 4080-pixel row crosses only
sixty-odd displacements. The loop now walks those runs. Within one, `sy` is
fixed, so the source row address -- a 64-bit multiply by the stride at every
pixel -- is computed once, and a run that falls outside the frame is skipped
whole. `sx = x + shift` is inside the frame exactly while x is in
`[-shift, w - shift)`, so intersecting that with the run turns four compares and
a branch per pixel into two clamps per run. The contributing pixel count is then
the length of what is left rather than an increment. And the tile column, which
the previous entry had memoised into a table and looked up per pixel, is a run
boundary now and is not looked up at all.

**On real captures, three alternating installs each:**

    old, per frame:  64.3, 72.0, 72.6 ms
    new, per frame:  50.0, 51.7, 58.7 ms

Non-overlapping, on real camera frames through `ZslCapture`, which is the same
quantity every accumulate figure in this log has been. 71.3 ms a frame to 51.7,
about 28% off. A synthetic burst harness, alternated four times each, agrees:
328 ms to 254 for a seven-frame burst, run medians 322-335 against 252-255.

### The measurement was harder than the change

Three harnesses gave three answers, and only the last one was measuring the
loop.

**Nine samples said there was no effect.** The first A/B -- nine samples, two
alternations -- reported the median accumulate moving 60 ms to 58, a 3%
difference inside noise, and by this log's own rule that is a negative result to
be reverted. It was wrong. Twenty-five samples and four alternations on the same
two binaries found 21%. The phone's run-to-run spread is a factor of two, so the
median of nine still moved by a quarter between two runs of the *identical*
binary. A null result is only evidence when the measurement could have seen the
effect, and this log should say what its harness could resolve before it reports
that something changed nothing.

**Wireless installs were part of the problem.** Alternating the two builds meant
a three-minute, 68 MB install between them, and the phone's thermal state drifts
over three minutes. Pushing both APKs to `/data/local/tmp` once and installing
from the device's own storage costs about twenty seconds, so the two builds run
under conditions that have barely moved. That is the difference between four
alternations and one.

**The first control was measuring garbage collection.** An unchanged pass timed
alongside the samples is meant to show whether the machine itself got slower.
`lumaProxy` was the obvious candidate and the wrong one: it allocates a 6 MB
plane per call, so it got steadily slower through a run while nothing else did.
`setReference` replaced it -- unchanged native code, allocating nothing,
sweeping the same buffers through the same band scheduler.

**And the benchmark was measuring the phone giving up.** Timed back to back, the
accumulation cost 60 ms a frame where a real capture logs under 20. Full-frame
accumulations run without pause leave the memory system saturated in a way the
real loop never sees, because a capture spends twenty milliseconds aligning
between one frame and the next. Once the harness did the proxy and the alignment
in between -- untimed, but done -- its samples went from a factor-of-two spread
to 242-275 ms across twelve bursts. A benchmark that removes the gaps is not
measuring the same loop.

**What the control was worth in the end.** It confirmed the phone was in the
same state for both builds and it caught the run where it was not. It did not
sharpen the final comparison: at twelve samples of a ten-millisecond sweep its
own noise is larger than the drift it was there to remove. It earns its place as
a check, not as a divisor.

### Where the parity test was weak, and is not now

The merge parity test hands both implementations one displacement, shared by
every tile. That cannot catch the mistake this change could most easily have
made: the run boundaries are the old per-pixel tile lookup, grouped, and a
boundary drawn one pixel out would take a pixel's displacement from its
neighbour. With one displacement across the image, the neighbour's is the same
and the error is invisible.

So `BayerAccumulator` grew an `add(frame, field)` overload -- the native side has
always taken the search and the accumulation apart, and splitting the Kotlin one
the same way lets a test hand both the same displacements, including
displacements no aligner would return. `nativeAndKotlinMergeAgreeOnAVaryingField`
uses a field that changes tile to tile, pushes runs off each edge, and puts one
tile clean outside the frame. Zero differing pixels of 76800, and the mean
contribution agrees to four decimals -- which is the part that matters, because
it only agrees if the same pixels were *skipped* as well as the same arithmetic
done on the ones that were not.

### The phone as an instrument, and what it did to the suite

Over an afternoon of this the device degraded: memory fell to 1.2 GB free with
swap nearly exhausted, and battery temperature rose from 31 to 39 C. The
synthetic harness, which had been reporting a 254 ms burst, reported 466 with
the identical binary. Nothing about the code had changed.

`ZslStreamDeviceTest.repeatedCapturesTakeAConsistentTime` failed in the final
full run, asserting that the slowest of three develops is within 1.4x the
fastest and getting 902 against 580. It failed on the **old** build too, in the
same conditions, which is what makes it a statement about the phone rather than
the change -- and it is a test about develop, which this change does not touch.
It is the same symptom the full disk produced earlier in this log: the spread
goes first.

The suite is otherwise green: 332 unit tests, and 95 device tests of which 94
pass in this state.

### Interleaving sum and weight, which was tried and reverted

The obvious next move looked like this: `sum` and `weight` are separate 50 MB
planes, so every pixel of the accumulation touches two cache lines fifty
megabytes apart, and each thread keeps four streams in flight instead of three.
Putting them side by side as one array of pairs should halve the streams and put
both values a pixel needs on one line.

It was built -- one `Site {float sum; float weight;}` plane through `nCreate`,
`nSetReference`, `nAddFrame` and `nFinish` -- and it is arithmetically identical:
both merge parity tests report zero differing pixels of 76800. And it makes no
measurable difference. Seven alternations across two runs, on both the synthetic
harness and real captures:

    synthetic, per burst:  planes 328 ms, interleaved 344 ms  (+5%)
    real, per frame:       planes 30.7 ms, interleaved 29.4 ms  (-4%)

The two disagree in sign, both sit inside a noise floor that was 20% at best,
and the run medians overlap completely. Reverted.

**The reasoning was wrong, and that is the part worth keeping.** Interleaving
does nothing for cache line traffic under sequential access, and this loop walks
x in order. Sixteen consecutive pixels touch one 64-byte line of `sum` and one
of `weight` -- two lines. Interleaved, the same sixteen pixels occupy 16 x 8 =
128 bytes -- also two lines. The same lines, the same bytes, the same order.
Array-of-structs beats struct-of-arrays when access is *random*, because then a
line fetched for one field carries the other; when access is sequential both
layouts stream perfectly and the transformation buys nothing. What was left was
four streams against three, which the prefetchers absorb without noticing.

That is worth an experiment's cost to have established rather than assumed, and
it is a better reason not to do it than the measurement is: the measurement
could not have resolved 5% in any case.

## The accumulation, four pixels at a time

With the run loop a flat span of pixels and no branch in it, NEON was the thing
left to try, and unlike the interleaving it had a mechanism behind it: the
accumulation is not bandwidth-bound. `setReference` sustains about 13 GB/s over
the same buffers through the same band scheduler while the accumulate manages a
third of that, so there was headroom for wider arithmetic to show.

**Why the compiler had not already done it.** `-O3 -ffast-math` vectorises most
things of this shape unprompted, and it left this loop alone. The reason is one
term: `noise[refRaw]` is a load whose address comes from the data, and NEON has
no gather instruction. One data-dependent load is enough to stop the whole loop
being vectorised, even though everything around it is trivially parallel.

So the gather stays scalar -- four `vld1q_lane_f32`, straight from the table
into lanes with no round trip through the stack -- and everything either side of
it goes four wide. The weight's two arms are both evaluated and one selected
with `vbslq_f32`; the divide is the arm almost never taken, and computing it and
throwing it away beats a branch that mispredicts a few percent of twelve million
times. The contributed weight is widened to double and kept in two
`float64x2_t`, folded down once the band is finished.

**Three alternating installs each, on a phone at the same temperature or hotter
for the vector build in every pass:**

    real captures, per frame:  scalar 31.7, 31.7, 33.3 ms
                               NEON   23.4, 25.8, 26.7 ms

    synthetic, per burst:      scalar 261.5, 331.0, 357.5 ms
                               NEON   244.5, 246.5, 249.5 ms

Non-overlapping on both, which is the bar. About 17% off the real per-frame
accumulate and 25% off the synthetic burst.

**The vector build is also far steadier**, and that was not the point but may
matter more than the mean. Its three run medians span 244.5 to 249.5 -- two per
cent -- where the scalar build's span 261.5 to 357.5. A camera whose shutter
sometimes takes longer for no visible reason is worse than one uniformly
slower, an argument this log has already made about retained develop buffers and
a full disk. Three runs is thin evidence for a spread and it is the same
direction all three times.

### Two things to be careful about here

**The tail was the part at risk, and the parity test could not see it.** The
vector loop takes four pixels at a time and the scalar loop finishes the
remainder. At 320 wide the tile runs come out 64 pixels across, so the remainder
is almost always empty and a mistake at that boundary would never show. The
merge parity test now runs at 320, 322 and 326, which puts the tile edges off
the multiple of four and exercises remainders of one, two and three. Zero
differing pixels at all three widths, and the mean contribution agrees to four
decimals despite the reordered summation.

**A comment was written that the disassembly contradicted.** The first version
of this claimed the multiply and the add were kept separate rather than fused,
because the Kotlin reference rounds between them. The generated code says
otherwise: `fmla` in the vector loop -- and `fmadd` in the scalar loop, which
had been doing it since long before this change, because `-ffast-math`
contracts. Nothing was taken that was not already being taken, and what settles
it either way is the parity test rather than the intent. The comment now says
what the compiler does.

### On quoting absolute numbers across a session

The scalar build measured 50-59 ms a frame earlier in the day and 31.7-33.3 ms
in the run above. Same binary, same phone, same test. What moved was the
device: heat, free memory, and how much had been asked of it in the preceding
hour. Only the alternated comparison inside one run means anything, and figures
from different runs of this log should not be subtracted from each other.

95 device tests pass, including the develop-consistency assertion that had
failed earlier under memory pressure.

And one thing about the harness itself: `MergeSpeedDeviceTest` aligns on the JVM
where a capture takes the native path, which is 44 MB of garbage a burst that
gets collected on the same cores as the accumulation being timed. That is why it
reads high. Swapping it for `alignNative` was tried and could not be evaluated,
because by then the phone was short of memory and the samples ranged over a
factor of six. It should be done on a phone that has just been rebooted, and the
figures above re-taken with it.

## The JPEG encode, on every core instead of one

The encode was the largest single item in a capture at about 240 ms, and the
handover recorded three options: quality, a different encoder, or leaving it
alone. Two of those turned out to be answerable by measurement before writing
anything.

**There is no hardware JPEG encoder to reach for.** Not asserted from general
knowledge -- looked for, in every `media_codecs*.xml` on the device. Nothing
declares an image encoder. HEIC on this phone goes through the video encoder,
which is a change to what the file *is* rather than a faster way to make the
same file.

**Quality is a size lever, not a speed one.** This was the surprise. On
photograph-like content -- detail everywhere, noise almost nowhere, which is
what a merged capture from this app looks like -- the encode barely notices:

    q95  ~240 ms  1912 KB
    q90  ~208 ms  1161 KB
    q85  ~198 ms   863 KB
    q75  ~195 ms   617 KB

Below q90 the time stops moving while the file keeps shrinking, because what is
left is fixed work -- colour conversion and the DCT -- rather than entropy
coding. An earlier version of that measurement used heavy synthetic noise and
reported 407 ms at q95, which flattered the quality knob considerably: noise is
close to incompressible, and it is the one thing this pipeline exists to remove.
A benchmark whose input the app would never produce answers a question nobody
asked.

So the only real option was the third, and it needed the library.

### What was actually wrong with `Bitmap.compress`

Nothing, except the door. It reaches Skia's copy of libjpeg-turbo -- the same
library -- but through an interface with no handle on it: one call, one thread,
no restart interval. Twelve and a half megapixels cost about 240 ms there while
seven cores sit idle.

A JPEG's entropy-coded data is one long serial dependency, because each block's
DC coefficient is stored as a difference from the block before it. Restart
markers are the way out: they divide the scan into intervals that reset the DC
predictor, so intervals do not depend on each other and can be produced at the
same time.

So the image is cut into horizontal strips, each encoded on its own thread as
though it were an image in its own right, and the results are stitched: the
first strip's header, then every strip's entropy data with a restart marker
between, then the end-of-image. Two details make the seam invisible. Each strip
is given a restart interval equal to its own length in MCUs, so it emits no
markers internally and its data is exactly one interval. And the height in the
stitched header is patched from the first strip's to the whole image's.

    4080x3072 q95 framework: median 297 ms, 1912 KB
    4080x3072 q95 strips:    median  98 ms, 2013 KB

Three times faster, measured in one run with both encoders in the same binary
and the rounds alternated, so no install and no thermal drift comes into it. On
real captures through `ZslCapture` the effect is larger still, because a merged
photograph is smoother than that synthetic scene: `compress+write` went from
about 240 ms to **34-57 ms**.

### What it costs, which is not nothing

**The files are about 5% larger**, and that is a real trade rather than a
rounding error. The framework encoder makes a second pass over the image to
derive Huffman tables fitted to it; the strip encoder uses the standard ones,
because every strip has to share one table set for the stitch to be legal. Two
passes for five per cent is a fair deal when you have one thread and a poor one
when you have eight.

**The APK grew 331 KB**, 68.35 MB to 68.68 MB. The vendored source is 4.1 MB
across 256 files; the linker keeps only the 8-bit compressor. (The first count
taken was 519, which is what `find` reports on this exFAT volume: macOS writes
an AppleDouble sidecar next to every file, and half of what it found was those.)

**And it is the project's first third-party native dependency**, which is worth
saying plainly. Everything else in `cpp/` is written here and pinned to a Kotlin
reference by a parity test. libjpeg-turbo is not, and cannot be. What it gets
instead is `JpegEncodeParityTest`, which holds its output against the
framework's -- and that turned out to be a stronger check than expected.

### The pixels are identical, which was not the plan

The test was written expecting "close": two encoders, same settings, some
rounding between them. It compares after decoding and allows a worst channel
difference of 2. The measured difference is **zero**, at every size tried and
every strip count -- 640x480, 641x481, 700x393, 320x17, at 1, 2, 3 and 8
strips. Same quantisation tables, same 4:2:0 subsampling, same standard Huffman
tables, so the coefficients agree exactly and nothing is left to differ.

The seam check is the one that matters most and it asserts exact equality
outright: the same image encoded as 1, 2, 4, 5, 8 and 16 strips must decode
identically. If the number of threads could change the picture, the stitch would
depend on where the seams fell, which is the whole risk of the approach.

The awkward sizes are deliberate. A minimum coded unit is sixteen pixels square
once chroma is subsampled, so a height that is not a multiple of sixteen leaves
a short final strip, a width that is not leaves a partial MCU column in every
row, and 320x17 asks for more strips than there are MCU rows.

### Where the time is now

    develop breakdown: native+setup 290ms, rotate 0-66ms, encode+save 82-128ms

The encode is no longer the largest item in a capture; the native develop is.
That is the fourth time in this log that fixing the biggest thing has promoted
something else, and the fourth time the useful move was to split the timer
before optimising rather than after.

99 device tests pass, 332 unit tests pass.

## The develop, split into what it is actually made of

The handover said the native develop was the largest item left in a capture, at
290-330 ms, and said to split it before optimising. That advice had been earned
four times over and was worth taking a fifth.

"Native develop" was five stages behind one number, and four of them had never
been timed at all. Named, on the phone, on real captures:

    develop breakdown: setup 2ms, native 296ms, rotate 0ms, encode+save 140ms
      autoexposure 3ms, bitmap 1ms, render 199ms, defringe 0ms, sharpen 93ms
        black 15ms, hotpixels 14ms, shading 38ms, demosaic+tone 130ms
        luma 15ms, copies 11ms, sharpen 65ms

Two things fell out of that, and the second one is the reason the rule exists.

**The demosaic is not most of the develop.** It is 130 ms of 296, sharing that
figure with a black-level pass, a hot-pixel scan, a shading pass and a
sharpening pass. Starting from the assumption that the demosaic was the cost --
which is exactly what the handover warned against -- would have aimed the work
at 44% of the problem.

**And capture sharpening was 93 ms**, a third of the native develop and the
second largest item in a whole capture. Nothing had ever reported it. It lives
behind its own JNI call, inside a figure named after something else, and it had
been there since it was written.

### Sharpening, in place and four pixels at a time

Two things were wrong with it, and neither was the algorithm.

**It went through a full copy of the image.** Fifty megabytes out and fifty
back, on the stated grounds that a pixel's neighbours must be the original
values rather than already-sharpened ones. That reason is correct and the copy
was still unnecessary: every neighbour the loop reads comes from the luminance
plane, which is computed once from the untouched image and never written again.
The only pixel whose colour is read is the one being written. The copy was
guarding a hazard that cannot occur -- and timing it first, before deleting it,
is what turned "this looks unnecessary" into eleven milliseconds a capture.

**And the nine-tap box blur was scalar.** It is now four pixels at a time in
NEON, and bit-identical rather than close. That distinction mattered more than
usual here. The obvious way to vectorise a 3x3 box sum is to add the three rows
and then reduce, which changes the order the nine floats are added in; float
addition is not associative, so it would have produced a different picture from
the Kotlin reference the parity test pins this to. Adding them lane-wise instead
means lane j performs exactly the sequence the scalar loop performs for x+j.
`vcvtaq_s32_f32` rounds ties away from zero, which is what `std::lround` does,
so the rounding agrees too, and the parity test still reports **worst 0/255**.

    isolated harness, medians of nine rounds, four alternations
    before  103, 88, 93, 85 ms
    after    65, 54, 58, 57 ms

Those ranges do not overlap, which is the standard this log holds a speed claim
to. Getting them to not overlap took a second attempt at measuring.

### The first measurement was not good enough, and said so

The first attempt measured the sharpen stage through real captures, alternating
the two builds four times -- the method that has worked for everything else
here. It produced before-medians of 144-175 ms against after-medians of 77-106,
which is the right answer, but the pooled ranges overlapped: 96-218 against
55-136. A capture runs the camera, the merge, a DNG writer thread and a
MediaStore publish across the same cores, and its sharpen figure swings by a
factor of two between neighbouring shots.

`SharpenSpeedDeviceTest` runs the pass alone on a fixed picture instead, nine
rounds a run, and the ranges separate. The rule that a speed claim needs
non-overlapping ranges is not only a test to apply at the end; it is also a
statement about what the harness has to be able to see.

Two details of that harness are worth keeping. It restores the image from a
pristine copy between rounds, outside the clock, because sharpening now writes
over the pixels it reads and every round would otherwise sharpen an image the
last round had already sharpened. And the scene is detail without noise -- the
same one the JPEG speed test uses, for the same reason. Sharpening skips every
pixel below its threshold, so a flat scene would skip nearly everything and a
noisy one would sharpen everything, and neither is what a merged capture looks
like.

### The parity test could not have caught a mistake in the tail

`DevelopParityTest` compared native against Kotlin at one size, 64x48. The
vector loop walks four pixels at a time from x=1 and finishes the remainder one
at a time, and at width 64 that remainder is always two. A mistake in the
one-pixel or three-pixel tail would have passed.

It now runs at four widths. Two of them are odd, which no sensor is: an even
width can only ever leave a remainder of nought or two, so the odd tails are
unreachable from a real frame size and would have gone untested for as long as
the fixtures looked like sensors.

The same test now also renders each width a second time with sharpening off and
asserts the two differ. Without that it would pass just as happily on a fixture
too flat to sharpen -- silent about the very loop it exists to cover. It reports
328-388 pixels moved at each width, so it has teeth.

### A harness for the render, and what is wrong with it

The render is what is left: 199 ms of a capture. `DevelopSpeedDeviceTest` times
it alone, and three consecutive runs of one binary gave medians of 406, 414 and
415 ms, so alternated between builds it resolves a few per cent.

**It also reads about twice what a capture pays, and I could not find out why.**
Every stage is inflated -- black 55 against 15, hot pixels 49 against 14,
shading 82 against 38, demosaic and tone 200 against 130 -- including the two
whose work does not depend on the picture at all, so it is not the scene. Three
explanations were tried and none of them held:

  * **Clock ramp.** Running the rounds back to back rather than spaced moved the
    median from 433 ms to 364. Part of it, then, but not most of it -- and
    back-to-back rounds drift upward within a single run as the phone warms,
    from 227 ms to 380 across ten. The gap is kept because it is what makes the
    instrument repeatable.
  * **Exposure.** The first version of the harness fixed the gain at 2.6, which
    on that scene put nearly every pixel over the tone curve's knee and into its
    exponential shoulder. Letting auto-exposure choose showed how wrong that
    was -- it picks 0.62, a factor of four lower -- and the demosaic-and-tone
    figure did not move when it was corrected. A satisfying theory, measured,
    and wrong.
  * **Scheduling.** A capture holds a camera session and a compute test does
    not, so the obvious guess was that the test is treated as background work.
    Running it with the app's own Activity in the foreground made it slower, not
    faster: the preview then competes for the same cores.

So it goes in as a comparison instrument and is labelled as one, in the same way
`MergeSpeedDeviceTest` is. The capture path stays the authority on what a
photograph actually costs.

One thing that theory did catch: the harness originally passed no shading map,
and a null map takes a shortcut in which every gain is 1 and the interpolation
never runs. The shading stage read 11-34 ms against the 38-45 a capture logs --
the harness measuring a branch no capture takes.

### The phone degraded again, and the test said so

After forty minutes of sustained eight-core float work, a final capture run
reported develops of 1018, 1474 and 1252 ms where the same binary had measured
496-628 an hour earlier, and `repeatedCapturesTakeAConsistentTime` failed on its
own spread assertion. That is the test doing its job.

Ten minutes later it passed again at 900, 912 and 866 ms -- still roughly twice
the morning's figures, on every stage including the JPEG encode and the
MediaStore publish, neither of which anything touched today. The battery read
37.7 C throughout, which is not hot. Whatever the state is, it is device-wide,
it outlasts a short rest, and it is not visible in the two things this log knows
to watch.

So: **never subtract a figure taken in one run from one taken in another.** This
log has said that before and it keeps earning its place.

### Where the time is now, and what has not been measured

Measured on a rested phone, before the sharpening change:

    develop breakdown: setup 2ms, native 296ms, rotate 0ms, encode+save 140ms
      autoexposure 3ms, bitmap 1ms, render 199ms, defringe 0ms, sharpen 93ms
        black 15ms, hotpixels 14ms, shading 38ms, demosaic+tone 130ms

The sharpening change is measured two ways, both alternated: 85-103 ms down to
54-65 in the isolated harness, and 144-175 down to 77-106 through real captures.
So the native develop should now be around 260 ms and the render is clearly the
largest item in it.

**"Around 260" is arithmetic, not a measurement.** The phone degraded before a
rested capture run could be taken of the shipped build, and this log does not
promote a subtraction across runs into a figure. Take that reading first thing
next session, on a phone that has rested, before believing any number here about
the whole.

What is not in doubt is the shape: the render is the largest item, and inside it
demosaic-and-tone is the largest stage -- and that is still a bundle of two
things, which is the fifth time this log has arrived at that sentence.

102 device tests pass, 332 unit tests pass.

## Splitting the render, and an instrument that lied

The handover asked for two things: a rested capture reading of the shipped
build, and a split of `demosaic+tone` before optimising it. Both were done. One
of them produced an answer; the other produced a lesson about the instrument
that is worth more.

### The rested reading

Two days later, on a phone at 32 C and fully charged:

    develop breakdown: setup 6ms, native 271ms, rotate 0ms, encode+save 165ms
      render 227ms, sharpen 37ms
        black 14ms, hotpixels 27ms, shading 38ms, demosaic+tone 146ms

The handover predicted "about 260 ms of native develop, but that is arithmetic,
not a measurement". Measured: 271. The sharpening pass, 93 ms before the last
session's work, is 37.

Only the second shot of the first run is worth quoting. The first is cold, the
third had a spike, and every subsequent run drifted upward as the phone warmed:
nine more captures taken immediately afterwards gave a median render of 315 ms.
**A phone that has been idle gives one good capture and then stops being a
rested phone.**

### The tone stage is about half of the biggest thing in the develop

`demosaic+tone` cannot be split with a timer, because it is one fused loop. So
it was split with an ablation build: keep the demosaic, write its output
straight out so the compiler cannot delete it, and remove the colour matrix,
the exposure gain, the rendering curve and the display lookup.

    demosaic+tone, forty samples each, balanced ordering, four rounds
    with tone     median 199 ms, per-round 201, 198, 192, 202
    demosaic only median 109 ms, per-round 108, 106, 112, 242

So the tone stage is roughly 90 ms against the demosaic's 109. That is the
fifth time this log has split a bundle and found the half nobody suspected was
worth attacking -- the demosaic is not most of `demosaic+tone`.

(These are harness figures, which run high. On the rested capture the same stage
is 146 ms, so the real split is nearer 80 and 66.)

### Everything finer than that was wrong

Three more ablations were built to split the tone stage: matrix and exposure,
the rendering curve, the display lookup. They reported the colour matrix at 6
ms, the rendering curve at 129, the desaturation at nothing, and
`shoulderCurve`'s `std::exp` at **79 ms** -- which would have made a single
library call the largest identifiable operation in the develop.

That was a satisfying story and it was false.

`std::exp` was replaced with an inline series -- the usual 2^n times exp(f)
decomposition, checked on the host at a worst relative error of 3.3e-6 across
the whole domain the shoulder reaches, which is a sixteenth of an output byte.
The parity test still reported worst 0/255, and a deliberately broken version of
it (degree one instead of degree five) was rejected at worst 3/255, so the test
had real power over the path. Everything about the change was sound.

**It made no difference at all.** Balanced against the shipping build over four
rounds: 186 ms against 181.

### The instrument was manufacturing the difference

The finer ablations had all been run in a fixed order -- always build A, then B,
then C, then D within each round. Re-running one pair with the order alternated
made the 79 ms evaporate.

So the harness was tested against itself. The same APK, installed under two
names, alternated four rounds:

    head   per-round medians 188, 193, 232, 486
    headB  per-round medians 201, 191, 478, 222

**Two and a half times, out of nothing.** The three-runs-of-406-414-415
repeatability recorded last session was real and irrelevant: those three runs
shared one install, and an A/B comparison cannot. The reinstall is what
introduces the variance, and every comparison has to pay it.

Three rules came out of this, and they are in the harness's own documentation:

  * **Run an A/A before believing an A/B.** If the instrument can separate a
    binary from itself, it can separate anything.
  * **Alternate the order, not only the builds.**
  * **Trust separation, not medians.** The tone result survived because forty
    samples of each build did not overlap at all, twice, under balanced
    ordering. The 79 ms did not survive anything.

The inline exponential is reverted. It is arithmetically defensible, well
tested, and buys nothing measurable, which makes it the fifth experiment this
log has backed out rather than kept on faith.

### What this leaves

The tone stage is worth attacking and the exponential inside it is not the
reason. What remains unattributed is the 90 ms itself: the colour matrix, the
maxima and branches of the rendering curve, and the display lookup. Splitting
those needs a better instrument than the one that exists, and the first job is
to give the harness an A/A that passes.

102 device tests pass, 332 unit tests pass.

## Aimed at one phone, and at being worth showing

The target narrowed to a single Pixel 9 Pro XL — no Play Store, no other
devices, no release paperwork. That is a smaller job than it sounds, because
almost nothing here depended on any of it.

### What the app was actually like to pick up

It worked. 111 device tests and 356 unit tests passed on the phone, the shutter
produced a 12-frame merged raw capture, and the new control row rendered
correctly over a live preview with all five lenses.

And it would have demoed badly, for three reasons that had nothing to do with
whether the code was correct:

- **Its saved state was wrong.** `MERGE OFF`, `ZSL OFF`, `FRAMES 28`, left
  behind by the morning's measurement runs. Both headline features switched off
  and captures taking about five seconds. Worse, there was no way back: a set of
  controls someone has been playing with could only be undone by reinstalling.
- **The control row did not fit.** Nine settings, of which the phone showed
  about two and a half before the pinned openers. That reads as unfinished
  however carefully the rest is drawn — and it was invisible on the emulator,
  whose window is a different shape.
- **The timer and the guides were forgotten between launches**, alone among the
  settings.

None of that is visible from the code, the tests, or an emulator. It took
holding the thing.

### The readout, and the number that is not in it

The merge measures what it bought — the frames that went in, and how much of
them survived rejection — and both went to logcat. The status line now reads
`8 frames · 91% kept` on all three capture paths, and the millisecond counts
moved to the log where they were being read from anyway.

**The good number could not be had honestly.** "noise ÷3.5" is what a readout
like this wants to say. The improvement in signal-to-noise of a weighted mean is
`Σw / √(Σw²)`, which is *scale-invariant*: halving every weight changes it not at
all. Only the variation between weights matters, and only their mean is
recorded, so the mean cannot produce the figure. What is offered instead is the
effective frame count — the reference plus what the rest actually contributed —
because a burst of twelve that kept half of each is not a burst of twelve.

### Six controls, because a phone shows six

`A/B` and `GUARD` moved into the PRO panel, which gave that panel a meaning it
had lacked: *what you set once*, against *what you change between shots*.
Neither is touched while composing. That the move was a change to a list of data
rather than to a layout is the whole return on the restructuring done earlier —
`ControlBarTest` caught the ordering before it reached the phone.

Zero shutter lag now defaults on. It is the headline of this camera and
defaulting it off made the app feel slower than it is.

### A suspicion that was wrong, checked before acting on it

Turning ZSL on by default raised an obvious worry: the raw path is Camera2
rather than CameraX, so it is not bound to the activity lifecycle, and
`DisposableEffect(Unit)` does not fire on backgrounding. Show a friend, take a
call, come back to a dead camera.

It does not happen. The preview is a `SurfaceView` and
`surface.onDestroyed { zslSurface = null }` nulls the surface when the window
stops being visible; the effect that owns the stream is keyed on that surface,
so it tears the camera down and rebuilds it on return. Keying on the surface
rather than on lifecycle events is the better choice, because it also covers the
surface going away for any other reason — which is exactly what the comment
above that effect already said it was for.

Worth recording as a near miss: the fix for a bug that is not there would have
been a second teardown path racing the one that works.

359 unit tests pass. 114 device tests pass on the phone — with the capture
consistency check failing once at 38.6 C after four minutes of load and passing
on its own, which is the thermal flakiness this log has recorded twice before.

## Looking at the photographs, which nothing here had done

Every claim in this log about the pipeline had been a claim about *arithmetic*:
the native path matches the Kotlin reference, the parity is exact, the ranges do
not overlap. None of it is a claim that the pictures are any good. This is the
first time anyone opened the output and measured it.

Done during a night when the project volume was unreadable — macOS was refusing
the process access to the removable drive — so it used only the phone and four
captures pulled off it. Nothing was changed; there was no source to change.

### The output is good

From `MF_20260826_184811_zsl_28f.jpg`, a 28-frame handheld capture through the
raw path. The subject is a battery label on carpet, which is a better test than
it sounds: fine text, a barcode for high-frequency detail, a smooth painted body
for noise, carpet for random texture.

    noise, flattest tenth of patches   sd 1.64 / 255
    detail, mean |laplacian|           34.4  (median 21)
    clipped highlights                 0.00%
    crushed shadows                    0.00%

At 100% on the barcode, where artefacts surface first: no sharpening halos, no
demosaic mazing or zipper, no colour fringing, and **no ghosting across 28
handheld frames**. In the corner, where the shading correction multiplies by up
to 3.5: no vignetting, and no corner noise amplification, which is the thing
that correction risks.

### A demosaic bug that was not there

A first pass found a 2.3/255 even-odd difference in flat areas, in both rows and
columns, that did not scale with local contrast. That is the signature of
residual CFA structure — a demosaic failing to equalise the two greens — and it
would have been a real defect.

It was an artefact of the measurement. **Averaging the absolute value of a
difference turns zero-mean noise into a positive number.** The signed average is
0.27 and 0.51 levels on the two images and points in opposite directions.

The decisive test is the sign, because a real artefact has a consistent one:
16 of 40 flat patches one way on the first image, 26 of 40 on the second, p =
0.27 and 0.08. A coin flip. The demosaic is clean.

That is the third time in this log that a measurement has had to be checked
against the possibility that it was measuring itself.

### White balance renders warm, and now by a known amount

The label is genuinely neutral white, and the merged DNG proves it. Taking the
raw CFA over it, black-subtracted and normalised, and applying the camera's own
`AsShotNeutral` of `[0.4873, 1.0, 0.6934]` — R x2.052, B x1.442:

    raw over the label                      R/G 0.503   B/G 0.669
    x AsShotNeutral, linear                 R/G 1.033   B/G 0.964
    the same, sRGB encoded                  R/G 1.015   B/G 0.984   <- neutral
    what the app rendered, sRGB             R/G 1.090   B/G 0.932

**The comparison has to be made in one space.** A first attempt put the linear
figure beside the app's sRGB figure and read a difference off the two, which
means nothing, because gamma changes ratios. Encoded properly, a correctly
balanced render of this label sits at 1.015 / 0.984 and the app puts it at
1.090 / 0.932 — **warm by about 7% in R/G and 5% in B/G, in the space the eye
judges.**

That is not the sensor, the merge or the demosaic. It is `ColorProfile.calibrated`,
which logs `blend 0.73 toward daylight` on every capture: under warm indoor
light, blending toward daylight deliberately leaves the warmth in.

**So it is a tuning decision rather than a defect**, and both positions are
defensible — a photograph taken under tungsten arguably should look warm, and
full neutralisation looks clinical. But an app whose argument is colour from the
sensor's own characterisation, rendering a known-neutral label visibly warm, is
worth a second look. It is now measurable either way, which it was not before.

One sample. Worth a grey card under two lightings before changing anything.

### The raw path is not just faster than the fallback, it is better

    raw ZSL path    4080x3072   noise sd 1.64   detail 34.4   clipped 0.00%
    YUV merge path  1600x1200   noise sd 0.20   detail 10.0   clipped 4.47%

1.9 megapixels against 12.5, a third of the detail, and one of the two YUV
captures blew four and a half per cent of its highlights. That path is what runs
when zero-shutter-lag is off, which was the default until it was changed on
28 August. **That change was a picture-quality decision as much as a speed one**,
and the entry above it claims only the speed.

## An instrument that can be believed, and the first thing measured with it

The last session left two jobs on the pipeline: make the shading pass cheaper,
and build a harness that can tell one build from another. They turned out to be
one job, because there was no way to demonstrate the first without the second.

### Why the old harness could not have shown this

Every A/B in this log until now compared two installs, and the develop harness
pointed at its own binary separates it from itself by two and a half times. The
reinstall is where the variance lives. Shading is 38 ms of a 230 ms develop, so
the win available was smaller than the noise by an order of magnitude.

So both implementations of the pass were built into the binary and timed back to
back inside one process, the way `JpegEncodeSpeedDeviceTest` holds both encoders.
`nShadingBench` runs forty rounds, alternating which of the two goes first,
returning the two times per round and a comparison of the two output planes.

**Pooled ranges are the wrong test here, and the A/A is what proved it.** Running
the fast path against itself:

    left   median 39ms   range 27-72ms      run two:  31ms   22-57ms
    right  median 38ms   range 28-61ms                35ms   20-66ms
    won 19 of 40 rounds                               15 of 40

Those ranges overlap almost completely, and they would overlap however large the
real difference was — the phone wanders by a factor of nearly three over the
eight seconds the test takes. But **the wander is shared by two runs a few
milliseconds apart**, so the within-round comparison sees straight through it.
19 and 15 of 40 is what a fair instrument looks like, and it is the claim the
A/A had to fail to make, because it is the claim the A/B would go on to make.

That is the replacement for the rule this log has been using. Separation of
pooled ranges is the right test across installs, where nothing pairs. Within one
process, pair the runs and check the pairing itself for bias.

### The shading pass, 1.53x

`shadingGain` was recomputing three things twelve and a half million times: the
row's position in the grid, which is constant along a row; the column's, which
is identical for every row; and the four corner gains, which hold for the whole
240-pixel run a cell covers. Lifting all three out leaves one table read and the
interpolation.

    per-pixel  median 60ms   range 41-92ms     run two:  59ms   39-79ms
    hoisted    median 39ms   range 26-69ms               39ms   24-68ms
    won 39 of 40 rounds, ratio median 1.53x             38 of 40, 1.54x

Both runs are above, because one is not evidence here. The two rounds the fast
path lost were a tie at 0.99x and a 0.85x, and the worst disagreement between
the two output planes came back at 4.76e-07 in both runs.

On the real capture path shading now reads 22-34 ms. The log's earlier 38 ms
came from a different run and cannot be subtracted from it — `black` and
`hotpixels` both read differently in this run too, and neither was touched.

### It was written to be bit-exact and it is not

Every operation survives the rearrangement in the same order and the same
association, which under IEEE arithmetic makes the two answers identical. The
first version of the test asserted exactly that, and failed: a fifth of the
plane came back different.

**The build compiles with `-ffast-math`.** That licenses the compiler to
reassociate and to fuse a multiply and an add into one instruction, and it takes
that licence differently in the two loops because their surroundings differ.
Writing the same expression twice does not produce the same instructions. The
worst disagreement across 500 million values is 4.8e-07 — one unit in the last
place, on numbers running to 3.5, before a gamma encode and an 8-bit quantise.

So the test states a bound instead of assuming exactness, and says what the
bound is for: a wrong corner, a run reading its neighbour's gains, an off-by-one
in the grid would all show as whole numbers, not last places.

**This is the fourth time in this log that a measurement had to be checked
against the possibility that it was measuring itself** — and the first time the
answer was the compiler rather than the phone.

### What the parity test could not have caught

`DevelopParityTest` renders 64x48, where a cell of a five-column map is four
pixels wide. The run-hoisting it is checking never gets a run longer than four.
The bench compares the two implementations at 4080x3072 against a 17x13 map,
where a run is 240 pixels, which is the case the code was written for. The
per-pixel form is kept in the binary for exactly this: it is what the fast path
is a rearrangement *of*, and something has to say so.

359 unit tests pass. 116 device tests pass on the phone, with
`repeatedCapturesTakeAConsistentTime` failing once at 40.7 C after five minutes
of load and passing on its own — the thermal flakiness this log has now
recorded three times.

## Outstanding for release

- [ ] Privacy policy: fill in effective date, developer name, contact; host at a
      public HTTPS URL (not a PDF)
- [ ] Create and back up a real release keystore — losing it means never being
      able to update the listing
- [ ] Complete the Play Data safety form (required even though nothing is
      collected)
- [x] Trademark sighting on "Multiframe" — descriptive, *MultiFrames* exists on
      Play in unrelated categories, and there is a live-looking US registration
      (serial 74107359, engineering analysis software). Recorded in
      [NAMES.md](NAMES.md), which already recommends *Coadd* instead
- [ ] Proper clearance search on the **chosen** name, which is a different job:
      the USPTO database directly, the registers for any market that matters,
      and an attorney. What has been done is web searching, and it is labelled
      as such
- [ ] Store screenshots, content rating, developer verification
