#!/usr/bin/env python3
"""Synthetic ground-truth bursts for the 2x sub-pixel evidence bench.

A known chart -- two slanted edges, a sinusoidal Siemens star, a
ColorChecker-like patch grid, a neutral random texture band-limited to
1.2 cycles/sensor pixel and a coloured slanted edge -- is rendered at four
samples per sensor pixel, filtered by a Gaussian optical PSF and the
one-pixel box aperture in the Fourier domain, shifted by a known sub-pixel
translation per frame (an exact Fourier phase ramp), point-sampled at the
sensor's pixel centres, mosaicked GBRG and given Poisson-Gaussian noise.

The truth for the 2x canvas is the same filtered scene sampled at half-pixel
spacing: the best any 2x reconstruction of this sensor could recover, since
the pixel aperture is irreducible. Coordinates: sensor pixel x has its centre
at x; canvas cell j has its centre at j/2 -- the SubPixel canvas convention.

Camera RGB = scene linear sRGB / WB (red and blue less sensitive, as the
Pixel's WB of about 1.6/2.4 says), so the analysis applies the WB and an
identity matrix. This tests the reconstruction's colour, not a colour matrix.

Deterministic: every burst's shifts and noise come from named seeds; the
manifest records them and every file's sha256.
"""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np

W, H, OS = 640, 480, 4
BLACK, WHITE = 256, 4095
RANGE = WHITE - BLACK
CFA = (1, 2, 0, 1)            # GBRG: colour at (y&1)*2+(x&1); 0 R, 1 G, 2 B
WB = np.array([1.6, 1.0, 2.4])
EXPOSURE = 0.85               # scene 1.0 green -> 85% of range

OPTICS = {'sharp': 0.35, 'soft': 0.70}   # Gaussian PSF sigma, sensor pixels
NOISE = {                     # variance DN^2 = k * (DN - black) + r^2
    'none': (0.0, 0.0),
    'low': (0.25, 1.5),       # around ISO 50-100 on a 2.4 um binned site
    'high': (4.0, 8.0),       # around ISO 1600
}
MOTION = {                    # name: (frames, kind)
    'hand8': (8, 'hand'),
    'hand16': (16, 'hand'),
    'grid16': (16, 'grid'),
    'static8': (8, 'static'),
}
CASES = [('sharp', 'hand8'), ('sharp', 'hand16'), ('sharp', 'grid16'),
         ('sharp', 'static8'), ('soft', 'hand8')]

# Chart geometry, sensor pixels. Shared with the analysis through chart.json.
CHART = {
    'edgeV': {'box': [24, 24, 128, 128], 'angleDeg': 5.0, 'dark': 0.05, 'light': 0.60},
    'edgeH': {'box': [24, 176, 128, 128], 'angleDeg': 5.0, 'dark': 0.05, 'light': 0.60},
    'star': {'centre': [320, 164], 'radius': 136, 'cycles': 72, 'mean': 0.30, 'amplitude': 0.25},
    'texture': {'box': [476, 24, 140, 128], 'mean': 0.30, 'amplitude': 0.12, 'cutoff': 1.2, 'seed': 77},
    'colourEdge': {'box': [476, 176, 140, 128], 'angleDeg': 5.0,
                   'left': [0.45, 0.06, 0.05], 'right': [0.05, 0.22, 0.40]},
    'patches': {'origin': [24, 330], 'size': [90, 30], 'gap': [10, 8], 'columns': 6},
    'background': 0.18,
}
# ColorChecker 24, sRGB 8-bit (BabelColor averages, D65), row-major.
CHECKER = [(115, 82, 68), (194, 150, 130), (98, 122, 157), (87, 108, 67), (133, 128, 177), (103, 189, 170),
           (214, 126, 44), (80, 91, 166), (193, 90, 99), (94, 60, 108), (157, 188, 64), (224, 163, 46),
           (56, 61, 150), (70, 148, 73), (175, 54, 60), (231, 199, 31), (187, 86, 149), (8, 133, 161),
           (243, 243, 242), (200, 200, 200), (160, 160, 160), (122, 122, 121), (85, 85, 85), (52, 52, 52)]


def srgb_to_linear(v):
    v = np.asarray(v, float) / 255
    return np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)


def coverage(d, pitch):
    """Area fraction on the positive side of a straight boundary at signed distance d."""
    return np.clip(0.5 + d / pitch, 0, 1)


def render_scene():
    """Linear sRGB scene at OS samples per sensor pixel; sample i sits at sensor i/OS."""
    pitch = 1.0 / OS
    y, x = np.mgrid[:H * OS, :W * OS] / OS
    scene = np.full((3, H * OS, W * OS), CHART['background'])

    # A broad low-contrast texture everywhere outside the targets, so the
    # aligner has something to hold on to, as it would in a photograph.
    rng = np.random.default_rng(5)
    spectrum = np.fft.rfft2(rng.normal(size=(H * OS, W * OS)))
    fy = np.fft.fftfreq(H * OS, d=pitch)[:, None]
    fx = np.fft.rfftfreq(W * OS, d=pitch)[None, :]
    f = np.hypot(fx, fy)
    broad = np.fft.irfft2(spectrum * ((f > 0.01) & (f < 0.12)), s=(H * OS, W * OS))
    broad *= 0.05 / broad.std()
    scene += broad[None]

    def box_mask(b, inset=0):
        bx, by, bw, bh = b
        return (x >= bx + inset) & (x < bx + bw - inset) & (y >= by + inset) & (y < by + bh - inset)

    for name, vertical in (('edgeV', True), ('edgeH', False)):
        e = CHART[name]; bx, by, bw, bh = e['box']
        cx, cy = bx + bw / 2, by + bh / 2
        a = np.deg2rad(e['angleDeg'])
        # Signed distance from the edge line through the box centre.
        d = ((x - cx) * np.cos(a) - (y - cy) * np.sin(a)) if vertical else \
            ((y - cy) * np.cos(a) + (x - cx) * np.sin(a))
        v = e['dark'] + (e['light'] - e['dark']) * coverage(d, pitch)
        m = box_mask(e['box'])
        scene[:, m] = v[m]

    s = CHART['star']; cx, cy = s['centre']
    r = np.hypot(x - cx, y - cy); th = np.arctan2(y - cy, x - cx)
    v = s['mean'] + s['amplitude'] * np.sin(s['cycles'] * th)
    v = np.where(r < 6, s['mean'], v)
    m = r <= s['radius']
    scene[:, m] = v[m]

    t = CHART['texture']
    tr = np.random.default_rng(t['seed']).normal(size=(H * OS, W * OS))
    band = np.fft.irfft2(np.fft.rfft2(tr) * (f < t['cutoff']), s=(H * OS, W * OS))
    m = box_mask(t['box'])
    band = band / band[m].std() * t['amplitude'] / 2
    scene[:, m] = t['mean'] + np.clip(band[m], -t['amplitude'], t['amplitude'])

    c = CHART['colourEdge']; bx, by, bw, bh = c['box']
    a = np.deg2rad(c['angleDeg'])
    d = (x - (bx + bw / 2)) * np.cos(a) - (y - (by + bh / 2)) * np.sin(a)
    k = coverage(d, pitch)
    m = box_mask(c['box'])
    for ch in range(3):
        scene[ch, m] = (c['left'][ch] + (c['right'][ch] - c['left'][ch]) * k)[m]

    p = CHART['patches']; ox, oy = p['origin']; pw, ph = p['size']; gx, gy = p['gap']
    lin = srgb_to_linear(CHECKER)
    for i, rgb in enumerate(lin):
        px, py = ox + (i % p['columns']) * (pw + gx), oy + (i // p['columns']) * (ph + gy)
        m = box_mask([px, py, pw, ph])
        for ch in range(3):
            scene[ch, m] = rgb[ch]
    return scene


def transfer(sigma):
    pitch = 1.0 / OS
    fy = np.fft.fftfreq(H * OS, d=pitch)[:, None]
    fx = np.fft.rfftfreq(W * OS, d=pitch)[None, :]
    return fx, fy, np.exp(-2 * np.pi ** 2 * sigma ** 2 * (fx ** 2 + fy ** 2)) * np.sinc(fx) * np.sinc(fy)


def shifts(kind, n, seed):
    if kind == 'static':
        return [(0.0, 0.0)] * n
    if kind == 'grid':
        return [(i % 4 * 0.5, i // 4 * 0.5) for i in range(n)]
    rng = np.random.default_rng(1000 + seed)
    return [(0.0, 0.0)] + [tuple(float(v) for v in rng.uniform(-2, 2, 2)) for _ in range(n - 1)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('output', type=Path)
    ap.add_argument('--seeds', type=int, default=3)
    a = ap.parse_args()
    out = a.output; out.mkdir(parents=True, exist_ok=True)
    scene = render_scene()
    spectra = [np.fft.rfft2(scene[c]) for c in range(3)]
    (out / 'chart.json').write_text(json.dumps(dict(CHART, checkerSrgb8=CHECKER, width=W, height=H,
        black=BLACK, white=WHITE, cfa=CFA, wb=WB.tolist(), exposure=EXPOSURE, optics=OPTICS,
        noise=NOISE), indent=1) + '\n')
    yy, xx = np.mgrid[:H, :W]
    site = np.array(CFA)[(yy & 1) * 2 + (xx & 1)]
    manifest = []
    for optics in sorted({o for o, _ in CASES}):
        fx, fy, mtf = transfer(OPTICS[optics])
        filtered = [np.fft.irfft2(s * mtf, s=scene.shape[1:]) for s in spectra]
        # Truths in linear scene units: 1x at pixel centres, 2x at half-pixel spacing.
        for name, step in (('truth-1x', OS), ('truth-2x', OS // 2)):
            t = np.stack([f[::step, ::step] for f in filtered], -1).astype('<f4')
            (out / f'{optics}-{name}.f32').write_bytes(t.tobytes())
        for (o, motion) in CASES:
            if o != optics:
                continue
            frames, kind = MOTION[motion]
            for noise in NOISE:
                seeds = range(a.seeds) if (kind == 'hand' or noise != 'none') else [0]
                twins = [0] if noise == 'none' else [0, 1]
                for seed in seeds:
                    motion_list = shifts(kind, frames, seed)
                    for twin in twins:
                        name = f'{optics}-{motion}-{noise}-s{seed}t{twin}'
                        d = out / 'bursts' / name; d.mkdir(parents=True, exist_ok=True)
                        rng = np.random.default_rng([seed, twin, list(NOISE).index(noise), frames, 7])
                        k, r = NOISE[noise]
                        props = dict(width=W, height=H, black=BLACK, white=WHITE, cfa=','.join(map(str, CFA)),
                                     frames=frames, optics=optics, motion=motion, noise=noise, seed=seed, twin=twin)
                        hashes = {}
                        for i, (dx, dy) in enumerate(motion_list):
                            ramp = np.exp(-2j * np.pi * (fx * dx + fy * dy))
                            planes = [np.fft.irfft2(s * mtf * ramp, s=scene.shape[1:])[::OS, ::OS] for s in spectra]
                            camera = np.choose(site, [planes[0] / WB[0], planes[1] / WB[1], planes[2] / WB[2]])
                            signal = RANGE * EXPOSURE * np.clip(camera, 0, None)
                            sigma = np.sqrt(k * signal + r * r)
                            raw = np.clip(np.rint(BLACK + signal + rng.normal(size=signal.shape) * sigma), 0, WHITE)
                            path = d / f'frame-{i:02d}.raw16'
                            path.write_bytes(raw.astype('<u2').tobytes())
                            hashes[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
                            props[f'frame.{i}.dxSensor'] = dx
                            props[f'frame.{i}.dySensor'] = dy
                        (d / 'synthetic.properties').write_text(''.join(f'{k2}={v}\n' for k2, v in props.items()))
                        manifest.append(dict(burst=name, optics=optics, sigmaPsf=OPTICS[optics], motion=motion,
                                             noise=noise, seed=seed, twin=twin, shifts=motion_list, sha256=hashes))
                        print(name, flush=True)
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=1) + '\n')


if __name__ == '__main__':
    main()
