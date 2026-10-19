#!/usr/bin/env python3
"""Freeze known optical signals / displacement / independent noise, not photographs.

No learned imagery. Sinusoids include an analytic Gaussian optical PSF and a
one-sensor-pixel aperture integration. This is a controlled numerical target,
not a measurement of the Pixel lens PSF or its MTF.
"""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np

p = argparse.ArgumentParser()
p.add_argument('output', type=Path)
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=True)
w, h, black, white = 512, 384, 256, 4095
period = [(x*.5, y*.5) for y in range(4) for x in range(4)]
cases = [
    ('static-low', [(0.,0.)]*16, 2., False),
    ('static-high', [(0.,0.)]*16, 35., False),
    ('diverse-low', period, 2., False),
    ('diverse-high', period, 35., False),
    ('axis-only', [(x*.5,0.) for x in range(4)]*4, 2., False),
    ('near-static-high', [(0.,0.)]+[(.03*np.sin(i),.03*np.cos(i)) for i in range(1,16)], 35., False),
    ('moving-object', period, 2., True),
]

def signal(x, y, object_shift=0.):
    # Broad two-dimensional texture stabilises block matching. Fine strips
    # carry known spatial frequencies up to the native sensor Nyquist limit.
    v = np.full(np.broadcast_shapes(x.shape, y.shape), .34)
    for fx,fy,amplitude,phase in [(.007,.011,.055,.4),(.023,-.018,.045,1.2),
                                 (.061,.037,.035,.7),(.109,-.081,.035,2.)]:
        aperture = np.sinc(fx)*np.sinc(fy)
        optical = np.exp(-2*np.pi**2*.45**2*(fx*fx+fy*fy))
        v += amplitude*aperture*optical*np.sin(2*np.pi*(fx*x+fy*y)+phase)
    for row,frequency in enumerate([.12,.24,.34,.43]):
        mask=(y>=row*96+12)&(y<(row+1)*96-12)&(x>=32)&(x<320)
        attenuation=np.sinc(frequency)*np.exp(-2*np.pi**2*.45**2*frequency**2)
        v += mask*.10*attenuation*np.sin(2*np.pi*frequency*x+.3)
    # Flat shadow region, and a soft high-contrast object with known motion.
    v=np.where((x>350)&(y>230),.012,v)
    edge=.5*(1+np.tanh((x-(400+object_shift))/1.0))
    stripe=.5*(1-np.tanh((x-(436+object_shift))/1.0))
    v=np.where((x>330)&(y<200),.06+.65*edge*stripe,v)
    return v

yy,xx=np.mgrid[:h,:w]
hy,hx=np.mgrid[:h*2,:w*2]/2
manifest=[]
for index,(name,motion,sigma,moving) in enumerate(cases):
    out=a.output/name
    out.mkdir(exist_ok=True)
    rng=np.random.default_rng(9187+index)
    fields=dict(width=w,height=h,black=black,white=white,cfa='1,2,0,1',frames=16,sigmaDn=sigma)
    hashes={}
    for i,(dx,dy) in enumerate(motion):
        truth=signal(xx-dx,yy-dy, i*.75 if moving else 0.)
        raw=np.clip(np.rint(black+(white-black)*truth+rng.normal(0,sigma,(h,w))),0,white).astype('<u2')
        path=out/f'frame-{i:02d}.raw16'
        path.write_bytes(raw.tobytes()); hashes[path.name]=hashlib.sha256(path.read_bytes()).hexdigest()
        fields[f'frame.{i}.dxSensor']=dx; fields[f'frame.{i}.dySensor']=dy
    for name2,truth in [('truth-native',signal(xx,yy)),('truth-2x',signal(hx,hy))]:
        path=out/(name2+'.f32'); path.write_bytes(truth.astype('<f4').tobytes())
        hashes[path.name]=hashlib.sha256(path.read_bytes()).hexdigest()
    (out/'synthetic.properties').write_text('\n'.join(f'{k}={v}' for k,v in fields.items())+'\n')
    manifest.append(dict(scene=name,seed=9187+index,frames=16,sigmaDn=sigma,
        movingObject=moving,shiftUnits='sensor pixels',sha256=hashes))
(a.output/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
(a.output/'README.md').write_text('''Synthetic evidence only. Native 512×384 GBRG, black 256,
white 4095. Seven frozen 16-frame bursts. Independent Gaussian noise with exact
variance, no CFA noise correlation or fixed-pattern noise. Known global sensor
translations and one separately moving object. Linear monochrome signal gives
an explicit zero false-chroma target. Frequency targets are analytically
attenuated by Gaussian sigma 0.45 sensor pixels and the physical one-pixel box
aperture. Sharpness comparisons are against this known optical signal, not a
claim about actual Pixel optics. Native pixel centres at integer coordinates;
2× centres at half-integers. Compare interpolation on the same coordinates.
All seed, motion and hashes frozen before candidate comparisons.
''')
print(a.output)
