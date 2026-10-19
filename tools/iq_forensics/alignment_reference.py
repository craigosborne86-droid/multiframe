#!/usr/bin/env python3
"""Bounded benchmark-only LK on selected frozen tiles; never a production flow path.

Initial integer fields come from the actual C++ aligner. A float 2x2 guide removes
the uint8 proxy quantisation only in this reference experiment. Checkerboard-held
out pixels score each estimator; residuals are not a ground-truth motion error.
"""
import argparse
import csv
import json
import time
from pathlib import Path
import numpy as np

p = argparse.ArgumentParser()
p.add_argument("archive", type=Path)
p.add_argument("replay", type=Path)
p.add_argument("output", type=Path)
a = p.parse_args(); a.output.mkdir(parents=True, exist_ok=True)
m = json.loads((a.archive / "manifest.json").read_text())
yy, xx = np.mgrid[2:30, 2:30]; fit = ((xx + yy) % 2 == 0); score = ~fit

def sample(im, x, y):
    ix = np.floor(x).astype(int); iy = np.floor(y).astype(int)
    if ix.min() < 0 or iy.min() < 0 or ix.max()+1 >= im.shape[1] or iy.max()+1 >= im.shape[0]:
        return None
    tx = x-ix; ty = y-iy
    return (im[iy, ix]*(1-tx)+im[iy, ix+1]*tx)*(1-ty)+(im[iy+1, ix]*(1-tx)+im[iy+1, ix+1]*tx)*ty

records = []; start = time.monotonic()
for regime in range(len(m["requestedRegimes"])):
    name = f"regime{regime}-repeat0"; directory = a.archive/name
    frames = json.loads((directory/"frames.json").read_text())
    def guide(i):
        raw = np.memmap(directory/frames[i]["raw"], dtype="<u2", mode="r", shape=(m["height"],m["width"]))
        g = sum(raw[dy::2, dx::2].astype(np.float32) for dy in range(2) for dx in range(2))/4
        gain = frames[i]["iso"]*frames[i]["exposureNs"]/(frames[0]["iso"]*frames[0]["exposureNs"])
        return (g-np.mean(m["staticBlack"]))*255/((m["staticWhite"]-min(m["staticBlack"]))*gain)
    ref = guide(0)
    fields = list(csv.DictReader((a.replay/name/"alignment.csv").open()))
    for frame in range(1, len(frames)):
        alt = guide(frame)
        # Fixed evenly-spaced sample grid, independent of contrast/outcome.
        for f in fields:
            if int(f["frame"]) != frame: continue
            t=int(f["tile"]); tx=t%int(f["tilesX"]); ty=t//int(f["tilesX"])
            if tx%7 != 3 or ty%7 != 3: continue
            x=xx+tx*32; y=yy+ty*32; target=ref[y,x]
            gx=(ref[y,x+1]-ref[y,x-1])/2; gy=(ref[y+1,x]-ref[y-1,x])/2
            j=np.stack([gx[fit],gy[fit]],axis=1); h=j.T@j; eig=np.linalg.eigvalsh(h)
            initial=np.array([float(f["dxProxy"]),float(f["dyProxy"])])
            vf=initial+np.array([float(f["fracDxProxy"]),float(f["fracDyProxy"])])
            u=initial.copy(); valid=eig[0]>max(1e-8, eig[1]*1e-4)
            if valid:
                for iteration in range(6):
                    warped=sample(alt,x+u[0],y+u[1])
                    if warped is None: valid=False;break
                    step=np.linalg.solve(h,j.T@(target-warped)[fit]); length=np.linalg.norm(step)
                    if length>1:step/=length
                    u=np.clip(u+step,initial-2,initial+2)
            def residual(v):
                pred=sample(alt,x+v[0],y+v[1])
                return float(np.sqrt(np.mean((pred-target)[score]**2))) if pred is not None else float('nan')
            records.append(dict(burst=name,frame=frame,tile=t,iso=frames[0]["iso"],valid=valid,
                dxA=initial[0],dyA=initial[1],dxB=vf[0],dyB=vf[1],dxC=u[0],dyC=u[1],
                minEigenvalue=float(eig[0]),maxEigenvalue=float(eig[1]),
                residualA=residual(initial),residualB=residual(vf),residualC=residual(u) if valid else float('nan')))
with (a.output/'tiles.csv').open('w') as out:
    w=csv.DictWriter(out,fieldnames=records[0]);w.writeheader();w.writerows(records)
summary=[]
for regime in range(len(m['requestedRegimes'])):
    rows=[r for r in records if r['burst']==f'regime{regime}-repeat0' and r['valid']]
    row=dict(regime=regime,tiles=len(rows),invalid=sum(not r['valid'] for r in records if r['burst']==f'regime{regime}-repeat0'))
    for k in ['residualA','residualB','residualC']:
        values=np.array([r[k] for r in rows]);row[k]=dict(mean=float(values.mean()),median=float(np.median(values)),p95=float(np.percentile(values,95)))
    summary.append(row);print(row)
(a.output/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
(a.output/'README.md').write_text(f'''Benchmark-only reference: six bounded LK iterations on 28×28 proxy tiles at a fixed spatial sampling interval of seven tiles. Float 2×2 RAW guide; original C++ integer fields seed the solve. Fit/scoring pixels are disjoint checkerboard sets. Eigenvalue bound only avoids numerically singular solves. Residuals are RMS proxy codes on held-out pixels, not sensor displacement ground truth. This experiment does not alter Bayer sampling or claim optical resolution. Wall time {time.monotonic()-start:.2f}s, host only.\n''')
