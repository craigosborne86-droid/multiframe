# Multiframe — build log

A record of the session that built this app, written 2026-08-24.

**Multiframe** is an Android computational photography camera. Every shutter
press captures a burst of frames, aligns them to cancel handshake, and merges
them to reduce sensor noise. Inspired by the general technique Adobe's Project
Indigo demonstrates on iPhone; original code, name, icon and UI throughout.

- Package: `dev.multiframe.camera` (final — cannot change after publication)
- Target device for development: Pixel 9 Pro XL (`komodo`), Android 17 / API 37
- 17 commits, ~4,900 lines across 30 Kotlin files and 2 native files
- 49 unit tests passing

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

- **Capture is now the bottleneck at 1649 ms.** Frames arrive ~870 ms apart over
  a 5.6 s window because CameraX cannot stream `RAW_SENSOR` through
  `ImageAnalysis`. Too slow for moving subjects; rules out ZSL for raw.
- **8 frames, not 32.** Memory no longer prevents it (32 × 25 MB is fine
  natively) but sequential capture would mean a 6.4 s window.
- **The YUV path still exists** alongside the raw path, at 3.1 MP with assumed
  BT.601 range and sRGB gamma.
- **Portability is tested against simulated capability profiles**, not real
  Samsung or Oppo hardware. That needs a second physical device.
- **`estimatedSigmaAtMid` is not computed** in the native merge (reports 0).

---

## Next step (agreed, not started)

**Zero-shutter-lag raw ring buffer.** The sensor was probed and supports it:

```
RAW 4080x3072: minFrameDuration 33333us (30.0 fps)  stall 0us
```

The zero stall is decisive — a non-zero stall would mean a raw capture blocks
other streams. At 30 fps an 8-frame burst spans **233 ms** rather than 5.6 s,
a 24× tighter window, and the frames are already captured when the shutter is
pressed. This also makes 32 frames practical and should push noise reduction
closer to the theoretical 2.83×, since frames 33 ms apart agree far better than
frames 870 ms apart.

GPU (Vulkan compute + `HardwareBuffer`) comes after — it would shave the
1884 ms merge, but that is second-order until capture is fixed.

---

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
