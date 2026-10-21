#!/usr/bin/env python3
"""Scores SuperResEvidenceBench output: does the 2x canvas add detail over enlargement?

    sr_evidence_analyze.py synthetic <targets-dir> <bench-out-dir>
    sr_evidence_analyze.py real <bench-out-dir>

Every 1x output is enlarged onto the canvas grid (cell j at sensor j/2, the
SubPixel convention) by separable Lanczos-3 and Keys bicubic. Metrics are on
linear light before any tone, sharpening or chroma smoothing, so the two paths
are compared on what they reconstructed, not on how they were developed.

Synthetic (ground truth): slanted-edge MTF50 (luma, ISO 12233-style 4x
oversampled ESF), aliasing on a sinusoidal Siemens star (angular energy
outside the star's own order, against the truth's fundamental), CIEDE2000 on
24 ColorChecker-like patches, luma SNR on the six neutral patches, RMSE
against the 2x truth, and the coherence of two disjoint half-bursts.

Real (no ground truth): edge 10-90% width at matched strong edges, power above
the 1x Nyquist frequency, the part of it two disjoint half-bursts agree on
(coherent power -- repeatable detail, not noise), high-pass false-colour
energy, and low-pass CIEDE2000 between the 2x and the enlarged merge.

The A/A null comes first: every difference is judged against the same
method's difference from itself on independent data (noise twins on the
synthetic, disjoint halves on the real). If the null separates, the metric is
void for that configuration.

NumPy and Pillow only.
"""
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw



def t_crit(df):
    """Two-sided Student t at alpha 0.01 (Cornish-Fisher; 3.91 at five degrees of freedom against 4.03 exact)."""
    z = 2.5758
    return z + (z ** 3 + z) / (4 * df) + (5 * z ** 5 + 16 * z ** 3 + 3 * z) / (96 * df ** 2) if df > 0 else float('inf')


# ---------------------------------------------------------------- resampling

def _axis_resample(im, axis, kernel, support):
    im = np.moveaxis(im, axis, 0)
    n = im.shape[0]
    x = np.arange(n * 2) / 2
    ix = np.floor(x).astype(int)[:, None] + np.arange(-support + 1, support + 1)[None, :]
    d = x[:, None] - ix
    w = kernel(d)
    w /= w.sum(axis=1, keepdims=True)
    out = np.sum(im[np.clip(ix, 0, n - 1)] * w.reshape(w.shape + (1,) * (im.ndim - 1)), axis=1)
    return np.moveaxis(out, 0, axis)


def lanczos3(d):
    return np.where(np.abs(d) < 3, np.sinc(d) * np.sinc(d / 3), 0)


def bicubic(d, a=-0.5):
    d = np.abs(d)
    return np.where(d <= 1, (a + 2) * d ** 3 - (a + 3) * d ** 2 + 1,
                    np.where(d < 2, a * d ** 3 - 5 * a * d ** 2 + 8 * a * d - 4 * a, 0))


def enlarge(im, kind):
    k, s = (lanczos3, 3) if kind == 'lanczos' else (bicubic, 2)
    return _axis_resample(_axis_resample(im, 0, k, s), 1, k, s)


def undo_box_prefilter(im):
    """Divides the canvas's 1:6:1 half-pixel box back out (analysis-only counterfactual, never production)."""
    ky = np.fft.fftfreq(im.shape[0])[:, None]; kx = np.fft.fftfreq(im.shape[1])[None, :]
    h = (6 + 2 * np.cos(2 * np.pi * ky)) / 8 * (6 + 2 * np.cos(2 * np.pi * kx)) / 8
    return np.stack([np.fft.ifft2(np.fft.fft2(im[..., c]) / h).real for c in range(im.shape[2])], -1)


def gaussian(im, sigma):
    if sigma <= 0:
        return im
    r = int(math.ceil(3 * sigma))
    k = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2); k /= k.sum()
    out = im
    for axis in (0, 1):
        pad = [(0, 0)] * out.ndim; pad[axis] = (r, r)
        p = np.pad(out, pad, mode='edge')
        out = sum(k[i] * np.take(p, range(i, i + out.shape[axis]), axis=axis) for i in range(2 * r + 1))
    return out


def bilinear(im, x, y):
    h, w = im.shape[:2]
    x = np.clip(x, 0, w - 1.001); y = np.clip(y, 0, h - 1.001)
    x0 = np.floor(x).astype(int); y0 = np.floor(y).astype(int)
    tx = x - x0; ty = y - y0
    return ((im[y0, x0] * (1 - tx) + im[y0, x0 + 1] * tx) * (1 - ty) +
            (im[y0 + 1, x0] * (1 - tx) + im[y0 + 1, x0 + 1] * tx) * ty)


# ---------------------------------------------------------------- colour

def luma(rgb):
    return rgb @ np.array([0.2126, 0.7152, 0.0722])


def lab(rgb):
    m = np.array([[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722], [0.0193, 0.1192, 0.9505]])
    xyz = np.clip(rgb, 0, None) @ m.T / np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > (6 / 29) ** 3, np.cbrt(xyz), xyz / (3 * (6 / 29) ** 2) + 4 / 29)
    return np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])], -1)


def de2000(lab1, lab2):
    L1, a1, b1 = np.moveaxis(lab1, -1, 0); L2, a2, b2 = np.moveaxis(lab2, -1, 0)
    C1 = np.hypot(a1, b1); C2 = np.hypot(a2, b2); Cb = (C1 + C2) / 2
    G = 0.5 * (1 - np.sqrt(Cb ** 7 / (Cb ** 7 + 25 ** 7)))
    a1p = (1 + G) * a1; a2p = (1 + G) * a2
    C1p = np.hypot(a1p, b1); C2p = np.hypot(a2p, b2)
    h1 = np.degrees(np.arctan2(b1, a1p)) % 360; h2 = np.degrees(np.arctan2(b2, a2p)) % 360
    dL = L2 - L1; dC = C2p - C1p
    dh = np.where(np.abs(h2 - h1) <= 180, h2 - h1, np.where(h2 <= h1, h2 - h1 + 360, h2 - h1 - 360))
    dh = np.where(C1p * C2p == 0, 0, dh)
    dH = 2 * np.sqrt(C1p * C2p) * np.sin(np.radians(dh / 2))
    Lb = (L1 + L2) / 2; Cbp = (C1p + C2p) / 2
    hb = np.where(np.abs(h1 - h2) > 180, (h1 + h2 + 360) / 2, (h1 + h2) / 2)
    hb = np.where(C1p * C2p == 0, h1 + h2, hb)
    T = (1 - 0.17 * np.cos(np.radians(hb - 30)) + 0.24 * np.cos(np.radians(2 * hb)) +
         0.32 * np.cos(np.radians(3 * hb + 6)) - 0.20 * np.cos(np.radians(4 * hb - 63)))
    SL = 1 + 0.015 * (Lb - 50) ** 2 / np.sqrt(20 + (Lb - 50) ** 2)
    SC = 1 + 0.045 * Cbp; SH = 1 + 0.015 * Cbp * T
    RT = -2 * np.sqrt(Cbp ** 7 / (Cbp ** 7 + 25 ** 7)) * np.sin(np.radians(60 * np.exp(-((hb - 275) / 25) ** 2)))
    return np.sqrt((dL / SL) ** 2 + (dC / SC) ** 2 + (dH / SH) ** 2 + RT * (dC / SC) * (dH / SH))


def srgb_encode(v):
    v = np.clip(v, 0, 1)
    return np.where(v <= 0.0031308, 12.92 * v, 1.055 * v ** (1 / 2.4) - 0.055)


# ---------------------------------------------------------------- measures

def slanted_edge(y_img, scale, vertical):
    """MTF of a near-vertical (or near-horizontal) edge; frequency in cycles per sensor pixel."""
    im = y_img if vertical else y_img.T
    h, w = im.shape
    g = np.abs(np.diff(gaussian(im, scale), axis=1))
    xs = np.arange(w - 1) + 0.5
    # Centroid of the gradient within a few pixels of each row's peak, so the
    # noise elsewhere on the row cannot pull it.
    peak = np.argmax(g, axis=1)[:, None]
    g = np.where(np.abs(np.arange(w - 1)[None, :] - peak) <= 4 * scale, g, 0)
    centres = (g * xs).sum(1) / np.maximum(g.sum(1), 1e-12)
    rows = np.arange(h)
    b, a = np.polyfit(rows, centres, 1)
    for _ in range(2):  # refit after rejecting rows the noise threw off
        keep = np.abs(centres - (a + b * rows)) < 2 * scale
        b, a = np.polyfit(rows[keep], centres[keep], 1)
    yy, xx = np.mgrid[:h, :w]
    dist = (xx - (a + b * yy)) * math.cos(math.atan(b))
    bw = 0.25
    bins = np.round(dist / bw).astype(int)
    lo, hi = -int(12 * scale / bw), int(12 * scale / bw)
    sel = (bins >= lo) & (bins <= hi)
    counts = np.bincount(bins[sel] - lo, minlength=hi - lo + 1)
    sums = np.bincount(bins[sel] - lo, weights=im[sel], minlength=hi - lo + 1)
    esf = np.full(hi - lo + 1, np.nan); ok = counts > 0; esf[ok] = sums[ok] / counts[ok]
    idx = np.arange(len(esf)); esf = np.interp(idx, idx[ok], esf[ok])
    if esf[-1] < esf[0]:
        esf = esf[::-1]
    lsf = np.gradient(esf)
    centre = int(np.argmax(np.convolve(lsf, np.ones(5) / 5, 'same')))
    n = len(lsf); win = 0.54 + 0.46 * np.cos(np.pi * (idx - centre) / (n / 2))
    win[np.abs(idx - centre) > n / 2] = 0
    spec = np.abs(np.fft.rfft(lsf * win, n=4 * n))
    mtf = spec / spec[0]
    f_img = np.fft.rfftfreq(4 * n, d=bw)          # cycles per image pixel
    f = f_img * scale                             # cycles per sensor pixel
    # The central difference over two bins is a sinc in frequency; undo it.
    mtf = mtf / np.maximum(np.sinc(2 * f_img * bw), 0.2)
    below = np.where(mtf < 0.5)[0]
    i = below[0] if len(below) else len(f) - 1
    mtf50 = float(np.interp(0.5, [mtf[i], mtf[i - 1]], [f[i], f[i - 1]])) if i > 0 else 0.0
    at = {str(q): float(np.interp(q, f, mtf)) for q in (0.25, 0.5, 0.75)}
    rise = np.interp([0.1, 0.9], (esf - esf.min()) / max(esf.max() - esf.min(), 1e-12), idx * bw)
    return dict(mtf50=mtf50, mtfAt=at, rise1090Sensor=float((rise[1] - rise[0]) / scale),
                curve=[(float(a2), float(b2)) for a2, b2 in zip(f[f <= 1.2][::4], mtf[f <= 1.2][::4])])


def star_rings(y_img, truth_y, scale, star):
    """Fundamental and out-of-order angular energy per ring, relative to the truth's fundamental.

    Both images are cut to the star and enlarged twice more by Lanczos-3
    before the rings are read bilinearly, so the reading itself adds little
    angular energy of its own; the truth's residual is the floor and is
    reported beside every method.
    """
    cx, cy = star['centre']; n = star['cycles']; m = 4096; R = star['radius'] + 4
    th = np.arange(m) * 2 * np.pi / m
    def cut(img, s):
        x0, y0 = int((cx - R) * s), int((cy - R) * s)
        return enlarge(img[y0:y0 + int(2 * R * s), x0:x0 + int(2 * R * s)], 'lanczos'), x0, y0
    yi, xi, yi0 = cut(y_img, scale); ti, xt, yt0 = cut(truth_y, 2)
    rows = []
    for r in np.geomspace(9, 128, 48):
        ring = bilinear(yi, 2 * ((cx + r * np.cos(th)) * scale - xi), 2 * ((cy + r * np.sin(th)) * scale - yi0))
        tr = bilinear(ti, 2 * ((cx + r * np.cos(th)) * 2 - xt), 2 * ((cy + r * np.sin(th)) * 2 - yt0))
        c = np.fft.rfft(ring) / m; ct = np.fft.rfft(tr) / m
        fund_t = 2 * abs(ct[n])
        fund = 2 * abs(c[n])
        spur = np.sqrt(2 * (np.sum(np.abs(c[1:]) ** 2) - abs(c[n]) ** 2))
        rows.append(dict(r=float(r), f=float(n / (2 * np.pi * r)), modulation=float(fund / max(fund_t, 1e-9)),
                         alias=float(spur / max(fund_t / math.sqrt(2), 1e-9)),
                         truthFund=float(fund_t), fund=float(fund), spur=float(spur)))
    def band(lo, hi, key):
        v = [q[key] for q in rows if lo <= q['f'] < hi]
        return float(np.mean(v)) if v else float('nan')
    # Limiting resolution: scanning up from the coarse rings, the last ring
    # before the star's own order stops dominating -- fundamental RMS at least
    # twice everything else on the ring -- or its contrast falls under a tenth
    # of the chart's.
    limit = 0.0
    for q in sorted(rows, key=lambda q: q['f']):
        if q['fund'] / math.sqrt(2) < 2 * q['spur'] or q['fund'] < 0.1 * 2 * star['amplitude'] / 2:
            break
        limit = q['f']
    return dict(rings=rows, limitCyclesPerSensorPx=limit,
                modulationBelowNyquist=band(0.25, 0.5, 'modulation'), modulationAboveNyquist=band(0.5, 0.85, 'modulation'),
                aliasBelowNyquist=band(0.25, 0.5, 'alias'), aliasAboveNyquist=band(0.5, 0.85, 'alias'))


def colour_edge_profile(img, truth, edge):
    """CIEDE2000 between the colour edge's averaged profile and the truth's: bleed, not noise."""
    bx, by, bw, bh = edge['box']; i = 8
    win = np.s_[2 * (by + i):2 * (by + bh - i), 2 * (bx + i):2 * (bx + bw - i)]
    yy, xx = np.mgrid[win] / 2
    a = np.deg2rad(edge['angleDeg'])
    d = (xx - (bx + bw / 2)) * np.cos(a) - (yy - (by + bh / 2)) * np.sin(a)
    bins = np.round(d / 0.25).astype(int); sel = np.abs(bins) <= 24      # +/- 6 sensor px
    def profile(im):
        return np.stack([np.bincount(bins[sel] + 24, weights=im[win][..., c][sel], minlength=49) /
                         np.maximum(np.bincount(bins[sel] + 24, minlength=49), 1) for c in range(3)], -1)
    p, t = profile(img), profile(truth)
    de = de2000(lab(t), lab(p))
    return dict(meanDe00=float(de.mean()), maxDe00=float(de.max()))


def radial_bands(shape, scale):
    fy = np.fft.fftfreq(shape[0])[:, None] * scale
    fx = np.fft.fftfreq(shape[1])[None, :] * scale
    return np.hypot(fx, fy)                      # cycles per sensor pixel


def hann2(shape):
    return np.outer(np.hanning(shape[0]), np.hanning(shape[1]))


BANDS = {'mid': (0.05, 0.25), 'nearNyquist': (0.25, 0.5), 'aboveNyquist': (0.5, 0.9)}


def band_power(img, scale):
    win = hann2(img.shape)
    F = np.fft.fft2((img - img.mean()) * win)
    f = radial_bands(img.shape, scale); P = np.abs(F) ** 2 / win.sum() ** 2 * img.size
    return {k: float(P[(f >= lo) & (f < hi)].sum()) for k, (lo, hi) in BANDS.items()}


def coherence(a, b, scale):
    """Per band: coherence and cross-power of two independent estimates of the same scene."""
    win = hann2(a.shape)
    A = np.fft.fft2((a - a.mean()) * win); B = np.fft.fft2((b - b.mean()) * win)
    f = radial_bands(a.shape, scale); norm = a.size / win.sum() ** 2
    out = {}
    for k, (lo, hi) in BANDS.items():
        m = (f >= lo) & (f < hi)
        cross = np.sum(A[m] * np.conj(B[m]))
        out[k] = dict(coherence=float(abs(cross) ** 2 / (np.sum(np.abs(A[m]) ** 2) * np.sum(np.abs(B[m]) ** 2))),
                      crossPower=float(cross.real * norm),
                      power=float(0.5 * (np.sum(np.abs(A[m]) ** 2) + np.sum(np.abs(B[m]) ** 2)) * norm))
    # The share of the coherent power above Nyquist sitting on the CFA's own
    # lattice frequencies (|fx|, |fy| in {0, 0.5} cycles per sensor pixel):
    # a pattern of the sampling grid repeats in both halves and is not detail.
    fy = np.fft.fftfreq(a.shape[0])[:, None] * scale; fx = np.fft.fftfreq(a.shape[1])[None, :] * scale
    near = lambda v, t: np.abs(np.abs(v) - t) < 0.02
    lo, hi = BANDS['aboveNyquist']; band = (f >= lo) & (f < hi)
    lattice = band & ((near(fx, .5) & (near(fy, 0) | near(fy, .5))) | (near(fy, .5) & near(fx, 0)))
    cross = (A * np.conj(B)).real
    out['aboveNyquist']['latticeCrossPower'] = float(cross[lattice].sum() * norm)
    out['aboveNyquist']['latticeAreaFraction'] = float(lattice.sum() / max(band.sum(), 1))
    return out


def phase_correlate(a, b, up=20):
    """Shift d (rows, cols) such that b(x + d) ~ a(x), to 1/up of a pixel."""
    win = hann2(a.shape)
    A = np.fft.fft2((a - a.mean()) * win); B = np.fft.fft2((b - b.mean()) * win)
    R = np.conj(A) * B; R /= np.maximum(np.abs(R), 1e-12)
    c = np.fft.ifft2(R).real
    p = np.array(np.unravel_index(np.argmax(c), c.shape), float)
    p = np.where(p > np.array(c.shape) / 2, p - np.array(c.shape), p)
    # Refine on an upsampled DFT around the integer peak (matrix-multiply DFT).
    n = int(math.ceil(up * 1.5)); off = n // 2
    rows = (np.arange(n) - off) / up + p[0]; cols = (np.arange(n) - off) / up + p[1]
    ky = np.fft.fftfreq(a.shape[0]); kx = np.fft.fftfreq(a.shape[1])
    ey = np.exp(2j * np.pi * np.outer(rows, ky)); ex = np.exp(2j * np.pi * np.outer(kx, cols))
    cc = (ey @ R @ ex).real
    q = np.unravel_index(np.argmax(cc), cc.shape)
    return np.array([rows[q[0]], cols[q[1]]])


def fourier_shift(img, d):
    """img(x + d), d in (rows, cols) pixels, every channel."""
    ky = np.fft.fftfreq(img.shape[0])[:, None]; kx = np.fft.fftfreq(img.shape[1])[None, :]
    ramp = np.exp(2j * np.pi * (ky * d[0] + kx * d[1]))
    if img.ndim == 2:
        return np.fft.ifft2(np.fft.fft2(img) * ramp).real
    return np.stack([np.fft.ifft2(np.fft.fft2(img[..., c]) * ramp).real for c in range(img.shape[2])], -1)


def false_colour(rgb, scale):
    """High-pass chroma RMS (opponent R-G, B-G, normalised by local luma), sensor-pixel sigma 2."""
    y = np.maximum(luma(rgb), 1e-3)
    opp = np.stack([(rgb[..., 0] - rgb[..., 1]) / gaussian(y, 4 * scale),
                    (rgb[..., 2] - rgb[..., 1]) / gaussian(y, 4 * scale)], -1)
    hp = opp - gaussian(opp, 2 * scale)
    yh = (y - gaussian(y, 2 * scale)) / gaussian(y, 4 * scale)
    return dict(chromaHighpassRms=float(np.sqrt(np.mean(hp ** 2))), lumaHighpassRms=float(np.sqrt(np.mean(yh ** 2))))


def paired(d_ab, d_aa):
    """The A/A null first, then the A/B claim: a paired t on each, and the effect against the null's spread."""
    d_ab = np.asarray(d_ab, float); d_aa = np.asarray(d_aa, float)
    def t(d):
        sd = d.std(ddof=1) if len(d) > 1 else float('nan')
        return float(d.mean() / (sd / math.sqrt(len(d)))) if sd and sd > 0 else (0.0 if d.mean() == 0 else float('inf'))
    t_aa, t_ab = t(d_aa), t(d_ab)
    null_ok = abs(t_aa) < t_crit(len(d_aa) - 1)
    spread = float(np.max(np.abs(d_aa))) if len(d_aa) else 0.0
    # The effect has to clear the null's own spread, not just its mean.
    separates = null_ok and abs(t_ab) >= t_crit(len(d_ab) - 1) and abs(d_ab.mean()) > 2 * float(np.std(d_aa))
    return dict(meanAB=float(d_ab.mean()), sdAB=float(d_ab.std(ddof=1)) if len(d_ab) > 1 else 0.0, tAB=t_ab,
                meanAA=float(d_aa.mean()), maxAbsAA=spread, tAA=t_aa, nullHolds=bool(null_ok), separates=bool(separates))


# ---------------------------------------------------------------- synthetic

def load(path, h, w):
    return np.fromfile(path, '<f4').reshape(h, w, 3).astype(np.float64)


def synthetic(targets, out_root):
    chart = json.loads((targets / 'chart.json').read_text())
    W, H = chart['width'], chart['height']; wb = np.array(chart['wb']); expo = chart['exposure']
    to_scene = wb / expo
    truths = {o: np.fromfile(targets / f'{o}-truth-2x.f32', '<f4').reshape(2 * H, 2 * W, 3).astype(np.float64)
              for o in chart['optics']}
    results = []
    root = out_root / 'synthetic'
    for d in sorted(p for p in root.iterdir() if (p / 'summary.properties').is_file()):
        optics, motion, noise, st = d.name.split('-')
        seed, twin = int(st[1:st.index('t')]), int(st[st.index('t') + 1:])
        truth = truths[optics]; ty = luma(truth)
        one = {k: load(d / f'{k}.f32', H, W) * to_scene for k in ('single', 'merge-est', 'merge-known')}
        two = {k: load(d / f'{k}.f32', 2 * H, 2 * W) * to_scene for k in ('sr-est', 'sr-known', 'sr-gross-oracle')
               if (d / f'{k}.f32').is_file()}
        methods = {'sr-est': two['sr-est'], 'sr-known': two['sr-known'],
                   'merge-lanczos': enlarge(one['merge-est'], 'lanczos'),
                   'merge-bicubic': enlarge(one['merge-est'], 'bicubic'),
                   'merge-known-lanczos': enlarge(one['merge-known'], 'lanczos'),
                   'single-lanczos': enlarge(one['single'], 'lanczos'),
                   'single-bicubic': enlarge(one['single'], 'bicubic'),
                   'sr-known-noprefilter': undo_box_prefilter(two['sr-known'])}
        if 'sr-gross-oracle' in two:
            methods['sr-gross-oracle'] = two['sr-gross-oracle']
        if seed == 0 and twin == 0:
            methods['truth'] = truth
        row = dict(burst=d.name, optics=optics, motion=motion, noise=noise, seed=seed, twin=twin,
                   gate={l.split('=')[0]: l.split('=', 1)[1] for l in (d / 'summary.properties').read_text().split('\n') if '=' in l},
                   methods={})
        al = np.genfromtxt(d / 'alignment.csv', delimiter=',', names=True, dtype=None, encoding=None)
        err = np.hypot(al['dxSensorEstimated'] - al['dxSensorTrue'], al['dySensorEstimated'] - al['dySensorTrue'])
        tiles_x = al['tilesX'][0]; tx = al['tile'] % tiles_x; tyy = al['tile'] // tiles_x
        interior = (tx > 0) & (tx < tiles_x - 1) & (tyy > 0) & (tyy < al['tile'].max() // tiles_x)
        row['alignmentErrorSensorPx'] = dict(mean=float(err[interior].mean()), median=float(np.median(err[interior])),
                                             p95=float(np.percentile(err[interior], 95)),
                                             fittedFraction=float(np.mean(al['fitted'] == 'true')) if al['fitted'].dtype.kind in 'US' else float(np.mean(al['fitted'])))
        for name, img in methods.items():
            y = luma(img); m = {}
            for e, vertical in (('edgeV', True), ('edgeH', False)):
                bx, by, bw, bh = chart[e]['box']; i = 12
                m[e] = slanted_edge(y[2 * (by + i):2 * (by + bh - i), 2 * (bx + i):2 * (bx + bw - i)], 2, vertical)
            m['mtf50'] = float(np.mean([m['edgeV']['mtf50'], m['edgeH']['mtf50']]))
            m['star'] = star_rings(y, ty, 2, chart['star'])
            s = chart['star']; cx, cy = s['centre']
            yy, xx = np.mgrid[:2 * H, :2 * W] / 2
            ann = (np.hypot(xx - cx, yy - cy) > 10) & (np.hypot(xx - cx, yy - cy) < s['radius'] - 4)
            m['starFalseColourRms'] = float(np.sqrt(np.mean((img[ann] - y[ann][:, None]) ** 2)))
            bx, by, bw, bh = chart['texture']['box']; win = np.s_[2 * (by + 6):2 * (by + bh - 6), 2 * (bx + 6):2 * (bx + bw - 6)]
            m['textureRmse'] = float(np.sqrt(np.mean((y[win] - ty[win]) ** 2)))
            m['starRmse'] = float(np.sqrt(np.mean((y[ann] - ty[ann]) ** 2)))
            bx, by, bw, bh = chart['colourEdge']['box']; win = np.s_[2 * (by + 6):2 * (by + bh - 6), 2 * (bx + 6):2 * (bx + bw - 6)]
            m['colourEdgeRmse'] = float(np.sqrt(np.mean((img[win] - truth[win]) ** 2)))
            m['colourEdgeDe00'] = colour_edge_profile(img, truth, chart['colourEdge'])['meanDe00']
            m['colourEdgeDe00Max'] = colour_edge_profile(img, truth, chart['colourEdge'])['maxDe00']
            # Patches: mean colour error and neutral-patch SNR.
            p = chart['patches']; ox, oy = p['origin']; pw, ph = p['size']; gx, gy = p['gap']; i = 6
            des, snr, snr_binned = [], [], []
            for k in range(24):
                px, py = ox + (k % p['columns']) * (pw + gx), oy + (k // p['columns']) * (ph + gy)
                win = np.s_[2 * (py + i):2 * (py + ph - i), 2 * (px + i):2 * (px + pw - i)]
                des.append(float(de2000(lab(truth[win].reshape(-1, 3).mean(0)), lab(img[win].reshape(-1, 3).mean(0)))))
                if k >= 18:
                    v = y[win]; snr.append(float(v.mean() / v.std()))
                    b = v[: v.shape[0] // 2 * 2, : v.shape[1] // 2 * 2].reshape(v.shape[0] // 2, 2, v.shape[1] // 2, 2).mean((1, 3))
                    snr_binned.append(float(b.mean() / b.std()))
            m['patchDe00Mean'] = float(np.mean(des)); m['patchDe00Max'] = float(np.max(des)); m['patchDe00'] = des
            m['neutralSnrCanvas'] = snr; m['neutralSnrSensorArea'] = snr_binned
            m['midGreySnrDb'] = float(20 * np.log10(snr_binned[3])) if snr_binned[3] > 0 else float('nan')
            row['methods'][name] = m
        # Native-resolution SNR: the 1x outputs before enlargement, per sensor pixel.
        p = chart['patches']; ox, oy = p['origin']; pw, ph = p['size']; gx, gy = p['gap']
        k = 21; px, py = ox + (k % 6) * (pw + gx), oy + (k // 6) * (ph + gy)
        for name in ('single', 'merge-est'):
            v = luma(one[name])[py + 6:py + ph - 6, px + 6:px + pw - 6]
            row['methods'].setdefault(name + '-native', {})['midGreySnrDb'] = float(20 * np.log10(v.mean() / v.std()))
        # Disjoint halves: coherence, registered by phase correlation and checked against the known shift.
        if (d / 'sr-estA.f32').is_file():
            row['halves'] = halves_synthetic(d, targets / 'bursts' / d.name, chart, H, W, to_scene, truth)
        results.append(row)
        print(d.name, 'MTF50', {k: round(v['mtf50'], 3) for k, v in row['methods'].items() if 'mtf50' in v}, flush=True)
    return results


def halves_synthetic(d, source, chart, H, W, to_scene, truth):
    props = dict(l.split('=', 1) for l in (source / 'synthetic.properties').read_text().split('\n') if '=' in l)
    shift = np.array([float(props['frame.1.dySensor']), float(props['frame.1.dxSensor'])])  # half B's reference
    out = {}
    bx, by, bw, bh = chart['texture']['box']
    win = np.s_[2 * (by + 8):2 * (by + bh - 8), 2 * (bx + 8):2 * (bx + bw - 8)]
    ty = luma(truth)[win]
    for name, scale_up in (('sr-est', False), ('merge-est', True)):
        a = load(d / f'{name}A.f32', 2 * H if not scale_up else H, 2 * W if not scale_up else W) * to_scene
        b = load(d / f'{name}B.f32', 2 * H if not scale_up else H, 2 * W if not scale_up else W) * to_scene
        if scale_up:
            a, b = enlarge(a, 'lanczos'), enlarge(b, 'lanczos')
        ya, yb = luma(a), luma(b)
        est = phase_correlate(ya[win], yb[win]) / 2      # canvas cells to sensor px
        # B's content is the scene displaced by B's reference shift; bring it to A's frame.
        yb_reg = fourier_shift(yb, 2 * shift)
        key = 'sr' if name == 'sr-est' else 'merge-lanczos'
        out[key] = dict(estimatedShiftSensor=est.tolist(), knownShiftSensor=shift.tolist(),
                        coherence=coherence(ya[win], yb_reg[win], 2),
                        coherenceEstimatedRegistration=coherence(ya[win], fourier_shift(yb, 2 * est)[win], 2))
    out['truthPower'] = band_power(ty, 2)
    return out


# ---------------------------------------------------------------- real

def real(out_root):
    rows = []
    for d in sorted(p for p in (out_root / 'real').iterdir() if (p / 'summary.properties').is_file()):
        s = dict(l.split('=', 1) for l in (d / 'summary.properties').read_text().split('\n') if '=' in l)
        wb = np.array([float(v) for v in s['wb'].split(',')]); wb3 = np.array([wb[0], (wb[1] + wb[2]) / 2, wb[3]])
        M = np.array([float(v) for v in s['matrix'].split(',')]).reshape(3, 3)
        margin = int(s['margin'])
        rois = [tuple(map(int, r.split(','))) for r in s['rois'].split(';')]
        burst = dict(burst=d.name, iso=int(s['iso']), gate={k: v for k, v in s.items() if 'Gate' in k or 'Holes' in k},
                     repeatIdentical=s.get('sr.repeatIdentical'), rois=[])
        alignment = np.genfromtxt(d / 'alignment.csv', delimiter=',', names=True, dtype=None, encoding=None)
        frac = lambda v: np.abs(v - np.round(v))
        burst['motion'] = dict(medianShiftSensor=float(np.median(np.hypot(alignment['dxSensor'], alignment['dySensor']))),
                               medianFractionSensor=float(np.median(np.hypot(frac(alignment['dxSensor']), frac(alignment['dySensor'])))),
                               fittedFraction=float(np.mean(alignment['fitted'] == 'true')) if alignment['fitted'].dtype.kind in 'US' else float(np.mean(alignment['fitted'])))
        for k, (x, y, w, h) in enumerate(rois):
            cw, ch = w + 2 * margin, h + 2 * margin
            def one(name):
                return (np.fromfile(d / f'roi{k}-{name}.f32', '<f4').reshape(ch, cw, 3).astype(np.float64) * wb3) @ M.T
            def two(name):
                return (np.fromfile(d / f'roi{k}-{name}.f32', '<f4').reshape(2 * ch, 2 * cw, 3).astype(np.float64) * wb3) @ M.T
            imgs = {'sr': two('sr'), 'srA': two('srA'), 'srB': two('srB'),
                    'merge-lanczos': enlarge(one('merge'), 'lanczos'), 'mergeA-lanczos': enlarge(one('mergeA'), 'lanczos'),
                    'mergeB-lanczos': enlarge(one('mergeB'), 'lanczos'), 'merge-bicubic': enlarge(one('merge'), 'bicubic'),
                    'single-lanczos': enlarge(one('single'), 'lanczos')}
            crop = np.s_[2 * margin:2 * margin + 2 * h, 2 * margin:2 * margin + 2 * w]
            ys = {n: luma(v) for n, v in imgs.items()}
            texture = float(np.std(ys['merge-lanczos'][crop] - gaussian(ys['merge-lanczos'], 4)[crop]) /
                            max(np.mean(ys['merge-lanczos'][crop]), 1e-6))
            r = dict(roi=[x, y, w, h], texture=texture, brightness=float(np.mean(ys['merge-lanczos'][crop])), methods={})
            edges = find_edges(ys['merge-lanczos'], crop)
            for n, v in imgs.items():
                r['methods'][n] = dict(edgeWidth=edge_widths(ys[n], edges), power=band_power(ys[n][crop], 2),
                                       **false_colour(v[crop], 2))
            r['edges'] = len(edges)
            # Coherence between disjoint halves, registered by phase correlation on the canvas.
            for method, a, b in (('sr', 'srA', 'srB'), ('merge-lanczos', 'mergeA-lanczos', 'mergeB-lanczos')):
                shift = phase_correlate(ys[a][crop], ys[b][crop])
                r['methods'][method]['halfShiftCanvas'] = shift.tolist()
                r['methods'][method]['halves'] = coherence(ys[a][crop], fourier_shift(ys[b], shift)[crop], 2)
            # Colour consistency at low pass: the 2x against the enlarged merge, and each against itself (halves).
            lp = lambda v: lab(np.clip(gaussian(v, 8), 0, None)[crop] / max(r['brightness'] * 4, 1e-6))
            r['lowpassDe00'] = {
                'sr-vs-merge': float(np.median(de2000(lp(imgs['merge-lanczos']), lp(imgs['sr'])))),
                'srA-vs-srB': float(np.median(de2000(lp(imgs['srA']), lp(fourier_shift(imgs['srB'], r['methods']['sr']['halfShiftCanvas']))))),
                'mergeA-vs-mergeB': float(np.median(de2000(lp(imgs['mergeA-lanczos']), lp(fourier_shift(imgs['mergeB-lanczos'], r['methods']['merge-lanczos']['halfShiftCanvas']))))),
            }
            burst['rois'].append(r)
        rows.append(burst)
        print(d.name, 'iso', burst['iso'], 'rois', len(burst['rois']), flush=True)
    return rows


def find_edges(y, crop, count=120):
    """Strong, isolated edges in the enlarged merge: (row, col, unit normal)."""
    g = gaussian(y, 1.5)
    gy, gx = np.gradient(g)
    mag = np.hypot(gx, gy)
    h0, h1, w0, w1 = crop[0].start + 20, crop[0].stop - 20, crop[1].start + 20, crop[1].stop - 20
    m = np.zeros_like(mag); m[h0:h1, w0:w1] = mag[h0:h1, w0:w1]
    thresh = np.percentile(m[h0:h1, w0:w1], 97)
    out = []
    flat = np.argsort(m, axis=None)[::-1]
    taken = np.zeros_like(m, bool)
    for idx in flat[:20000]:
        r, c = divmod(int(idx), m.shape[1])
        if m[r, c] < thresh or len(out) >= count:
            break
        if taken[max(r - 12, 0):r + 13, max(c - 12, 0):c + 13].any():
            continue
        taken[r, c] = True
        n = np.array([gy[r, c], gx[r, c]]) / max(mag[r, c], 1e-12)
        out.append((r, c, n))
    return out


def edge_widths(y, edges):
    """Median 10-90% rise, sensor pixels, along each edge's normal (canvas units halved)."""
    t = np.linspace(-16, 16, 129)
    widths = []
    for r, c, n in edges:
        p = bilinear(y, c + t * n[1], r + t * n[0])
        lo, hi = np.median(p[:24]), np.median(p[-24:])
        if hi - lo <= 0:
            continue
        q = (p - lo) / (hi - lo)
        if q.min() < -0.25 or q.max() > 1.25:   # not an isolated step
            continue
        try:
            i10 = np.where(q >= 0.1)[0][0]; i90 = np.where(q >= 0.9)[0][0]
        except IndexError:
            continue
        x10 = np.interp(0.1, q[i10 - 1:i10 + 1], t[i10 - 1:i10 + 1]) if i10 > 0 else t[0]
        x90 = np.interp(0.9, q[i90 - 1:i90 + 1], t[i90 - 1:i90 + 1]) if i90 > 0 else t[0]
        widths.append((x90 - x10) / 2)
    return float(np.median(widths)) if widths else float('nan')


# ---------------------------------------------------------------- panels

def panel(images, labels, path, gain, tile=256):
    pic = Image.new('RGB', (tile * len(images), tile + 18), (10, 10, 12))
    draw = ImageDraw.Draw(pic)
    for i, (im, label) in enumerate(zip(images, labels)):
        v = np.uint8(np.clip(srgb_encode(im * gain) * 255 + 0.5, 0, 255))
        pic.paste(Image.fromarray(v).resize((tile, tile), Image.Resampling.NEAREST), (i * tile, 18))
        draw.text((i * tile + 4, 3), label, fill=(230, 230, 230))
    pic.save(path)


def synthetic_panels(targets, out_root, report):
    chart = json.loads((targets / 'chart.json').read_text())
    W, H = chart['width'], chart['height']; to_scene = np.array(chart['wb']) / chart['exposure']
    truth = np.fromfile(targets / 'sharp-truth-2x.f32', '<f4').reshape(2 * H, 2 * W, 3)
    figures = []
    for burst in ('sharp-hand16-low-s0t0', 'sharp-hand8-high-s0t0', 'sharp-static8-low-s0t0'):
        d = out_root / 'synthetic' / burst
        if not d.is_dir():
            continue
        merge = enlarge(load(d / 'merge-est.f32', H, W) * to_scene, 'lanczos')
        single = enlarge(load(d / 'single.f32', H, W) * to_scene, 'lanczos')
        sr = load(d / 'sr-est.f32', 2 * H, 2 * W) * to_scene
        srk = load(d / 'sr-known.f32', 2 * H, 2 * W) * to_scene
        cx, cy = chart['star']['centre']
        for region, (x, y, s) in {'star-centre': (cx - 32, cy - 32, 64), 'texture': (480, 40, 64),
                                  'colour-edge': (514, 208, 64), 'edge': (56, 56, 64)}.items():
            cut = lambda im: im[2 * y:2 * (y + s), 2 * x:2 * (x + s)]
            path = report / f'synthetic-{burst}-{region}.png'
            panel([cut(truth), cut(single), cut(merge), cut(sr), cut(srk)],
                  ['truth 2x', 'single Lanczos', 'merge Lanczos', '2x estimated', '2x known shifts'], path, 1.0)
            figures.append(path.name)
    return figures


def real_panels(out_root, report, rows):
    figures = []
    for b in rows:
        d = out_root / 'real' / b['burst']
        s = dict(l.split('=', 1) for l in (d / 'summary.properties').read_text().split('\n') if '=' in l)
        wb = np.array([float(v) for v in s['wb'].split(',')]); wb3 = np.array([wb[0], (wb[1] + wb[2]) / 2, wb[3]])
        M = np.array([float(v) for v in s['matrix'].split(',')]).reshape(3, 3); margin = int(s['margin'])
        best = sorted(range(len(b['rois'])), key=lambda k: -b['rois'][k]['texture'])[:2]
        for k in best:
            x, y, w, h = b['rois'][k]['roi']; cw, ch = w + 2 * margin, h + 2 * margin
            one = lambda n: (np.fromfile(d / f'roi{k}-{n}.f32', '<f4').reshape(ch, cw, 3) * wb3) @ M.T
            two = lambda n: (np.fromfile(d / f'roi{k}-{n}.f32', '<f4').reshape(2 * ch, 2 * cw, 3) * wb3) @ M.T
            merge = one('merge')
            nearest = np.repeat(np.repeat(merge, 2, 0), 2, 1)
            imgs = [nearest, enlarge(merge, 'lanczos'), two('sr')]
            # The central 128x128 sensor pixels, enlarged to 2x in each panel.
            c0 = 2 * margin + w - 128
            cut = lambda im: im[c0:c0 + 256, c0:c0 + 256]
            gain = 0.45 / max(np.percentile(luma(cut(imgs[1])), 99), 1e-6)
            path = report / f'real-{b["burst"]}-roi{k}.png'
            panel([cut(i) for i in imgs], ['merge nearest', 'merge Lanczos', '2x canvas'], path, gain, tile=384)
            figures.append(path.name)
    return figures


# ---------------------------------------------------------------- tables

def fmt(v, digits=3):
    if isinstance(v, str):
        return v
    if v is None or (isinstance(v, float) and math.isnan(v)):
        return '--'
    if isinstance(v, float) and math.isinf(v):
        return 'inf'
    return f'{v:.{digits}f}'


def table(header, rows):
    out = ['| ' + ' | '.join(header) + ' |', '|' + '---|' * len(header)]
    out += ['| ' + ' | '.join(fmt(c) for c in r) + ' |' for r in rows]
    return '\n'.join(out) + '\n'


SYN_METRICS = [  # key, label, getter, higher is better
    ('mtf50', 'MTF50 (cy/px)', lambda m: m['mtf50'], True),
    ('limit', 'star limit (cy/px)', lambda m: m['star']['limitCyclesPerSensorPx'], True),
    ('modAbove', 'star modulation >Nyq', lambda m: m['star']['modulationAboveNyquist'], True),
    ('aliasBelow', 'star alias <Nyq', lambda m: m['star']['aliasBelowNyquist'], False),
    ('aliasAbove', 'star alias >Nyq', lambda m: m['star']['aliasAboveNyquist'], False),
    ('texRmse', 'texture RMSE', lambda m: m['textureRmse'], False),
    ('starFc', 'star false colour', lambda m: m['starFalseColourRms'], False),
    ('de', 'patch dE00 mean', lambda m: m['patchDe00Mean'], False),
    ('deMax', 'patch dE00 max', lambda m: m['patchDe00Max'], False),
    ('edgeDe', 'colour-edge profile dE00 mean', lambda m: m['colourEdgeDe00'], False),
    ('edgeDeMax', 'colour-edge profile dE00 max', lambda m: m['colourEdgeDe00Max'], False),
    ('snr', 'mid-grey SNR dB (sensor area)', lambda m: m['midGreySnrDb'], True),
]
SNR_METHODS = ['single-native', 'merge-est-native', 'merge-lanczos', 'sr-est', 'sr-known']
SYN_METHODS = ['truth', 'single-lanczos', 'merge-bicubic', 'merge-lanczos', 'merge-known-lanczos', 'sr-est', 'sr-known',
               'sr-known-noprefilter']


def tables(out_root):
    md = []
    syn = json.loads((out_root / 'synthetic-metrics.json').read_text()) if (out_root / 'synthetic-metrics.json').is_file() else []
    groups = {}
    for r in syn:
        groups.setdefault((r['optics'], r['motion'], r['noise']), []).append(r)
    truth = {r['optics']: r['methods']['truth'] for r in syn if 'truth' in r['methods']}
    verdicts = []
    for noise in ('none', 'low', 'high'):
        md.append(f'\n#### Synthetic, noise {noise}: means over seeds{" and twins" if noise != "none" else ""}\n')
        for key, label, get, _ in SYN_METRICS:
            if key == 'snr':
                if noise == 'none':
                    continue
                rows = [[f'{o}/{mo} ({len(rs)})'] + [float(np.mean([r['methods'][m]['midGreySnrDb'] for r in rs])) for m in SNR_METHODS]
                        for (o, mo, n), rs in sorted(groups.items()) if n == noise]
                md.append('\nmid-grey luma SNR, dB, per sensor-pixel area (1x native; 2x binned 2x2)\n\n' +
                          table(['config'] + SNR_METHODS, rows))
                continue
            rows = []
            for (optics, motion, n), rs in sorted(groups.items()):
                if n != noise:
                    continue
                row = [f'{optics}/{motion} ({len(rs)})']
                for meth in SYN_METHODS:
                    if meth == 'truth':
                        row.append(get(truth[optics]) if optics in truth else None); continue
                    vals = [get(r['methods'][meth]) for r in rs]
                    row.append(float(np.mean(vals)))
                rows.append(row)
            md.append(f'\n{label}\n\n' + table(['config'] + SYN_METHODS, rows))
        rows = []
        for (optics, motion, n), rs in sorted(groups.items()):
            if n != noise:
                continue
            ae = [r['alignmentErrorSensorPx'] for r in rs]
            g = [r['gate'] for r in rs]
            rows.append([f'{optics}/{motion}', float(np.mean([a['mean'] for a in ae])), float(np.mean([a['p95'] for a in ae])),
                         float(np.mean([a['fittedFraction'] for a in ae])),
                         f"{sum(x['sr-est.preGate'] == 'true' for x in g)}/{len(g)}", f"{sum(x['sr-est.postGate'] == 'true' for x in g)}/{len(g)}"])
        md.append('\nAlignment (production estimate vs known shift, interior tiles) and the coverage gate\n\n' +
                  table(['config', 'mean err (px)', 'p95 err (px)', 'tiles fitted', 'pre-gate accepts', 'post-gate accepts'], rows))
    # A/A null then A/B, noisy configurations: twins share shifts, differ in noise.
    md.append('\n#### A/A null and A/B, synthetic noisy bursts (pairs: 3 seeds x 2 twins)\n')
    rows = []
    for (optics, motion, noise), rs in sorted(groups.items()):
        if noise == 'none':
            continue
        by = {(r['seed'], r['twin']): r for r in rs}
        seeds = sorted({s for s, _ in by})
        for cand in ('sr-est', 'sr-known'):
            for key, label, get, higher in SYN_METRICS:
                if key in ('deMax',):
                    continue
                d_aa = [get(by[(s, 0)]['methods'][m]) - get(by[(s, 1)]['methods'][m]) for s in seeds for m in (cand, 'merge-lanczos')]
                d_ab = [get(by[(s, t)]['methods'][cand]) - get(by[(s, t)]['methods']['merge-lanczos']) for s in seeds for t in (0, 1)]
                if not all(np.isfinite(d_aa + d_ab)):
                    continue
                p = paired(d_ab, d_aa)
                better = (p['meanAB'] > 0) == higher
                verdict = ('void (null separates)' if not p['nullHolds'] else
                           (('2x better' if better else '2x worse') if p['separates'] else 'no difference'))
                rows.append([f'{optics}/{motion}/{noise}', cand, label, p['meanAB'], p['tAB'], p['maxAbsAA'], p['tAA'], verdict])
                verdicts.append(dict(config=f'{optics}/{motion}/{noise}', candidate=cand, metric=key, **p, verdict=verdict))
    md.append(table(['config', '2x', 'metric', 'mean(2x - merge Lanczos)', 't A/B', 'max |A/A|', 't A/A', 'verdict'], rows))
    # Coherence calibration.
    rows = []
    for (optics, motion, noise), rs in sorted(groups.items()):
        hs = [r['halves'] for r in rs if 'halves' in r]
        if not hs:
            continue
        for meth in ('sr', 'merge-lanczos'):
            for band in ('nearNyquist', 'aboveNyquist'):
                tp = np.mean([h['truthPower'][band] for h in hs])
                cp = np.mean([h[meth]['coherence'][band]['crossPower'] for h in hs])
                cpe = np.mean([h[meth]['coherenceEstimatedRegistration'][band]['crossPower'] for h in hs])
                co = np.mean([h[meth]['coherence'][band]['coherence'] for h in hs])
                pw = np.mean([h[meth]['coherence'][band]['power'] for h in hs])
                rows.append([f'{optics}/{motion}/{noise}', meth, band, cp / tp, cpe / tp, pw / tp, co])
        err = [np.hypot(*(np.array(h['sr']['estimatedShiftSensor']) - np.array(h['sr']['knownShiftSensor']))) for h in hs]
        rows.append([f'{optics}/{motion}/{noise}', 'registration', 'phase-corr error (px)', float(np.mean(err)), None, None, None])
    md.append('\n#### Half-burst coherence on the texture patch (calibration of the real-burst measure)\n\n' + table(
        ['config', 'method', 'band', 'coherent power / truth', 'same, estimated registration', 'total power / truth', 'coherence'], rows))

    if (out_root / 'real-metrics.json').is_file():
        md.append(real_tables(json.loads((out_root / 'real-metrics.json').read_text()), verdicts))
    (out_root / 'verdicts.json').write_text(json.dumps(verdicts, indent=1) + '\n')
    (out_root / 'tables.md').write_text(''.join(md))


REAL_METRICS = [
    ('edge', 'edge 10-90% (sensor px)', lambda m: m['edgeWidth'], False),
    ('hf', 'power >Nyq / mid', lambda m: m['power']['aboveNyquist'] / max(m['power']['mid'], 1e-30), None),
    ('fc', 'chroma high-pass RMS', lambda m: m['chromaHighpassRms'], False),
    ('yhp', 'luma high-pass RMS', lambda m: m['lumaHighpassRms'], None),
]


def real_tables(rows, verdicts):
    md = ['\n#### Real bursts: per burst, mean over windows\n']
    out = []
    for b in rows:
        rs = b['rois']
        def mean(f):
            v = [f(r) for r in rs]; v = [x for x in v if np.isfinite(x)]
            return float(np.mean(v)) if v else float('nan')
        coh = lambda meth, band, key: mean(lambda r: r['methods'][meth]['halves'][band][key])
        # Coherent power above the 1x Nyquist frequency: as a fraction of the
        # power there (how much of it is repeatable), and against the total
        # mid-band power (how much repeatable detail, scene-normalised).
        frac = lambda meth: mean(lambda r: r['methods'][meth]['halves']['aboveNyquist']['crossPower'] /
                                 r['methods'][meth]['halves']['aboveNyquist']['power'])
        rel = lambda meth: mean(lambda r: r['methods'][meth]['halves']['aboveNyquist']['crossPower'] /
                                r['methods'][meth]['halves']['mid']['power'])
        out.append([b['burst'], str(b['iso']), b['gate'].get('sr.preGate'), b['gate'].get('sr.postGate'),
                    b['motion']['medianShiftSensor'], b['motion']['medianFractionSensor'],
                    mean(lambda r: r['methods']['merge-lanczos']['edgeWidth']), mean(lambda r: r['methods']['sr']['edgeWidth']),
                    mean(lambda r: r['methods']['merge-lanczos']['power']['aboveNyquist'] / r['methods']['merge-lanczos']['power']['mid']),
                    mean(lambda r: r['methods']['sr']['power']['aboveNyquist'] / r['methods']['sr']['power']['mid']),
                    frac('merge-lanczos'), frac('sr'), rel('merge-lanczos'), rel('sr'),
                    sum(r['methods']['sr']['halves']['aboveNyquist']['latticeCrossPower'] for r in rs) /
                    sum(r['methods']['sr']['halves']['aboveNyquist']['crossPower'] for r in rs),
                    mean(lambda r: r['methods']['merge-lanczos']['chromaHighpassRms']), mean(lambda r: r['methods']['sr']['chromaHighpassRms']),
                    mean(lambda r: r['lowpassDe00']['sr-vs-merge']), mean(lambda r: r['lowpassDe00']['mergeA-vs-mergeB']),
                    mean(lambda r: r['lowpassDe00']['srA-vs-srB'])])
    md.append(table(['burst', 'ISO', 'pre', 'post', 'shift px', 'frac px', 'edge merge', 'edge 2x', 'P>Nyq merge', 'P>Nyq 2x',
                     'coherent fraction >Nyq merge', 'coherent fraction >Nyq 2x', 'coherent >Nyq / mid merge', 'coherent >Nyq / mid 2x', '2x coherent >Nyq on CFA lattice', 'false colour merge',
                     'false colour 2x', 'dE00 2x vs merge', 'dE00 merge A/A', 'dE00 2x A/A'], out))
    md.append('\n#### Real bursts: A/A null (same method, disjoint halves) then A/B (2x half vs merge half), windows pooled\n\n')
    trows = []
    for scope in ['all'] + [b['burst'] for b in rows]:
        sel = rows if scope == 'all' else [b for b in rows if b['burst'] == scope]
        for key, label, get, higher in REAL_METRICS + [('cohP', 'coherent power >Nyq / mid power', None, True),
                                                       ('cohF', 'coherent fraction of power >Nyq', None, True)]:
            d_aa, d_ab = [], []
            for b in sel:
                for r in b['rois']:
                    m = r['methods']
                    if key in ('cohP', 'cohF'):
                        den = 'mid' if key == 'cohP' else 'aboveNyquist'
                        c = lambda meth: m[meth]['halves']['aboveNyquist']['crossPower'] / m[meth]['halves'][den]['power']
                        d_ab.append(c('sr') - c('merge-lanczos'))
                        continue
                    va = [get(m[x]) for x in ('srA', 'srB', 'mergeA-lanczos', 'mergeB-lanczos')]
                    if not all(np.isfinite(va)):
                        continue
                    d_aa += [va[0] - va[1], va[2] - va[3]]
                    d_ab += [va[0] - va[2], va[1] - va[3]]
            if key in ('cohP', 'cohF'):
                # Already a between-halves quantity: its null is zero cross-power, tested directly.
                d = np.array(d_ab)
                t = float(d.mean() / (d.std(ddof=1) / math.sqrt(len(d)))) if len(d) > 1 and d.std() > 0 else 0.0
                verdict = ('2x more' if d.mean() > 0 else '2x less') if abs(t) >= t_crit(len(d) - 1) else 'no difference'
                trows.append([scope, label, float(d.mean()), t, None, None, verdict])
                continue
            if len(d_ab) < 3:
                continue
            p = paired(d_ab, d_aa)
            if higher is None:
                verdict = 'void (null separates)' if not p['nullHolds'] else (('2x higher' if p['meanAB'] > 0 else '2x lower') if p['separates'] else 'no difference')
            else:
                better = (p['meanAB'] > 0) == higher
                verdict = 'void (null separates)' if not p['nullHolds'] else (('2x better' if better else '2x worse') if p['separates'] else 'no difference')
            trows.append([scope, label, p['meanAB'], p['tAB'], float(np.std(d_aa)), p['tAA'], verdict])
            verdicts.append(dict(config='real/' + scope, candidate='sr-half', metric=key, **p, verdict=verdict))
    md.append(table(['scope', 'metric', 'mean(2x - merge)', 't A/B', 'sd A/A', 't A/A', 'verdict'], trows))
    return ''.join(md)


def main():
    mode = sys.argv[1]
    if mode == 'synthetic':
        targets, out_root = Path(sys.argv[2]), Path(sys.argv[3])
        rows = synthetic(targets, out_root)
        (out_root / 'synthetic-metrics.json').write_text(json.dumps(rows, indent=1) + '\n')
        (out_root / 'figures').mkdir(exist_ok=True)
        synthetic_panels(targets, out_root, out_root / 'figures')
    elif mode == 'real':
        out_root = Path(sys.argv[2])
        rows = real(out_root)
        (out_root / 'real-metrics.json').write_text(json.dumps(rows, indent=1) + '\n')
        (out_root / 'figures').mkdir(exist_ok=True)
        real_panels(out_root, out_root / 'figures', rows)
    elif mode == 'tables':
        tables(Path(sys.argv[2]))


if __name__ == '__main__':
    main()
