#!/usr/bin/env python3
"""Bounded six-iteration LK reference on frozen synthetic guide tiles, not production."""
import argparse,csv,json,time
from pathlib import Path
import numpy as np

p=argparse.ArgumentParser();p.add_argument('inputs',type=Path);p.add_argument('baseline',type=Path);p.add_argument('out',type=Path)
a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True)
w,h=512,384; yy,xx=np.mgrid[2:30,2:30];fit=(xx+yy)%2==0
def sample(im,x,y):
    ix=np.floor(x).astype(int);iy=np.floor(y).astype(int)
    if ix.min()<0 or iy.min()<0 or ix.max()+1>=im.shape[1] or iy.max()+1>=im.shape[0]:return None
    tx=x-ix;ty=y-iy
    return (im[iy,ix]*(1-tx)+im[iy,ix+1]*tx)*(1-ty)+(im[iy+1,ix]*(1-tx)+im[iy+1,ix+1]*tx)*ty
summary=[];start=time.monotonic()
for root in sorted(a.baseline.iterdir()):
    if not (root/'alignment.csv').is_file():continue
    def guide(i):
        raw=np.fromfile(a.inputs/root.name/f'frame-{i:02d}.raw16',dtype='<u2').reshape(h,w).astype(float)
        return sum(raw[dy::2,dx::2] for dy in range(2) for dx in range(2))/4
    ref=guide(0);rows=list(csv.DictReader((root/'alignment.csv').open()));out=[];err=[]
    for frame in range(1,16):
        alt=guide(frame)
        for row in rows:
            if int(row['frame'])!=frame:continue
            t=int(row['tile']);tx=t%8;ty=t//8;x=xx+tx*32;y=yy+ty*32
            target=ref[y,x];gx=(ref[y,x+1]-ref[y,x-1])/2;gy=(ref[y+1,x]-ref[y-1,x])/2
            j=np.stack([gx[fit],gy[fit]],axis=1);hh=j.T@j;eig=np.linalg.eigvalsh(hh)
            initial=np.array([float(row['dxProxy']),float(row['dyProxy'])]);u=initial.copy()
            valid=eig[0]>max(1e-8,eig[1]*1e-4)
            if valid:
                for _ in range(6):
                    warped=sample(alt,x+u[0],y+u[1])
                    if warped is None:valid=False;break
                    step=np.linalg.solve(hh,j.T@(target-warped)[fit]);length=np.linalg.norm(step)
                    if length>1:step/=length
                    u=np.clip(u+step,initial-2,initial+2)
            if not valid:u=initial+np.array([float(row['fracDxProxy']),float(row['fracDyProxy'])])
            integer=np.floor(u+.5).astype(int);fraction=u-integer
            out.append(dict(frame=frame,tile=t,dx=int(integer[0]),dy=int(integer[1]),fx=float(fraction[0]),fy=float(fraction[1]),valid=valid))
            if tx in [1,2,3,4] and ty in [1,2,3,4]:
                err.append(float(np.linalg.norm(2*u-np.array([float(row['trueDxSensor']),float(row['trueDySensor'])]))))
    directory=a.out/root.name;directory.mkdir(exist_ok=True)
    with (directory/'fields.csv').open('w') as f:
        writer=csv.DictWriter(f,fieldnames=list(out[0]));writer.writeheader();writer.writerows(out)
    summary.append(dict(case=root.name,meanErrorSensorPixels=float(np.mean(err)),median=float(np.median(err)),p95=float(np.percentile(err,95)),invalid=sum(not x['valid'] for x in out)))
    print(summary[-1])
(a.out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
(a.out/'README.md').write_text(f'Benchmark only. Six bounded LK iterations on a float 2×2 RAW guide, seeded by current integer alignment, on every 32×32 proxy tile (28×28 support). Same fit-pixel selection as archived reference. This is not dense optical flow, and has not been adopted in production. Host wall time {time.monotonic()-start:.3f}s.\n')
