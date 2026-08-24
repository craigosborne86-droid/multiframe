# Multiframe — build log

A record of the session that built this app, written 2026-08-24.

**Multiframe** is an Android computational photography camera. Every shutter
press captures a burst of frames, aligns them to cancel handshake, and merges
them to reduce sensor noise. Inspired by the general technique Adobe's Project
Indigo demonstrates on iPhone; original code, name, icon and UI throughout.

- Package: `dev.multiframe.camera` (final — cannot change after publication)
- Target device for development: Pixel 9 Pro XL (`komodo`), Android 17 / API 37
- ~7,500 lines across 36 Kotlin files and 3 native files
- 65 JVM unit tests and 13 on-device instrumentation tests passing

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
- **`estimatedSigmaAtMid` is not computed** in the native merge (reports 0).

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

### Still not verified

- **The shutter has not been pressed on the live stream.** Everything up to and
  including a running 30 fps raw stream is confirmed; the handover, merge and
  DNG/JPEG output from ring frames are not. An incoming call arrived on the
  test device and on-device work stopped there.
- The instrumentation suite has not been re-run since headroom became
  proportional. `burstForDepth(32)` is unchanged at 28, so the assertions should
  hold, but that is reasoning rather than a green run.
- Frames merged from the ring have not been compared against the sequential
  path for noise reduction, which is the measurement that shows whether a
  233 ms window actually agrees better than a 5.6 s one.

## Next step

**Phase 7: Vulkan compute Bayer merge.** The merge is now the bottleneck at
1884 ms, and the ring already holds frames in a form a GPU can consume. This is
where `AHardwareBuffer` earns its place over mmap: it imports into Vulkan
without a copy, which mmap'd pages cannot.

Before that, two things this phase left open: run the live stream on hardware,
and measure whether frames 33 ms apart merge measurably better than frames
870 ms apart.

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
