# Multiframe — build log

A record of the session that built this app, written 2026-08-24.

**Multiframe** is an Android computational photography camera. Every shutter
press captures a burst of frames, aligns them to cancel handshake, and merges
them to reduce sensor noise. Inspired by the general technique Adobe's Project
Indigo demonstrates on iPhone; original code, name, icon and UI throughout.

- Package: `dev.multiframe.camera` (final — cannot change after publication)
- Target device for development: Pixel 9 Pro XL (`komodo`), Android 17 / API 37
- ~21,000 lines across 95 Kotlin files and 6 native files
- 320 JVM unit tests and 80 on-device tests (67 running, 13 awaiting an unlocked screen)

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

**Finish the mosaic.** Registration, planning, compositing and writing the
result out are done and tested; what remains is orchestration rather than
algorithm — guided capture with a coverage grid, and locking exposure and white
balance across the sweep. The lock is worth doing early for a second reason: it
is what would let a mosaic carry an exposure and an ISO instead of omitting
them.

**Measure develop on a cool phone.** The instrumentation is in and the target is
identified, but every absolute taken so far came off a warm device with a
gallery full of test captures. What is owed is one clean run: charged, idle,
`DCIM/Multiframe` cleared, and the stage lines read straight out of logcat.
Until then the phase has a diagnosis and no numbers. It is the same shape of problem -- a per-pixel kernel
in one pass -- and it has a native implementation already, so the question is
what that implementation is spending its time on rather than which language it
is in.

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
