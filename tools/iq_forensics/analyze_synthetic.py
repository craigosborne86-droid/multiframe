#!/usr/bin/env python3
"""Known-signal error and spectral transfer, no arbitrary-photograph MTF claims."""
import argparse
import csv
import json
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw

p=argparse.ArgumentParser(); p.add_argument('inputs',type=Path); p.add_argument('outputs',type=Path)
a=p.parse_args(); w,h=512,384; black,span=256,3839

def interpolate(image):
    """Separable Lanczos-3 at x=j/2 (same origin as the SR canvas)."""
    def axis(im,which):
        im=np.moveaxis(im,which,0); x=np.arange(im.shape[0]*2)/2
        ix=np.floor(x).astype(int)[:,None]+np.arange(-2,4)[None,:]
        distance=x[:,None]-ix; weight=np.sinc(distance)*np.sinc(distance/3)
        weight[abs(distance)>=3]=0;weight/=weight.sum(axis=1)[:,None]
        result=np.sum(im[np.clip(ix,0,len(im)-1)]*weight.reshape(weight.shape+(1,)*(im.ndim-1)),axis=1)
        return np.moveaxis(result,0,which)
    return axis(axis(image,0),1)

metrics=[]; montage=[]
regions={'all':(16,16,480,352),'flat':(366,246,128,120),'detail':(48,20,248,344),'object':(350,20,140,168)}
for root in sorted(a.outputs.iterdir()):
    if not (root/'summary.properties').is_file():continue
    source=a.inputs/root.name
    truth=np.fromfile(source/'truth-native.f32',dtype='<f4').reshape(h,w)
    truth2=np.fromfile(source/'truth-2x.f32',dtype='<f4').reshape(h*2,w*2)
    images={k:np.fromfile(root/(k+'.rgbf32'),dtype='<f4').reshape(h,w,3)
        for k in ['single','native-baseline','native-metadata']}
    images['native-lanczos2x']=interpolate(images['native-baseline'])
    for k in ['sr-estimated','sr-known']+(['sr-lk'] if (root/'sr-lk.rgb16').exists() else []):
        images[k]=(np.fromfile(root/(k+'.rgb16'),dtype='<u2').reshape(h*2,w*2,3).astype(float)-black)/span
    row={'case':root.name,'regions':{},'spectral':{}}
    for region,(x,y,rw,rh) in regions.items():
        result={}
        for name,img in images.items():
            scale=img.shape[1]//w; roi=np.s_[y*scale:(y+rh)*scale,x*scale:(x+rw)*scale]
            target=(truth2 if scale==2 else truth)[roi]
            data=img[roi]; error=data-target[:,:,None]
            result[name]={'rmse':float(np.sqrt(np.mean(error**2))),
                'bias':float(error.mean()),'falseChromaRms':float(np.sqrt(np.mean((data-data.mean(axis=2,keepdims=True))**2)))}
        row['regions'][region]=result
    # Fit sine/cosine at the known stimulus frequency; no MTF50 inferred.
    for band,f in enumerate([.12,.24,.34,.43]):
        spectral={}
        for name,img in images.items():
            scale=img.shape[1]//w
            x=np.arange(48*scale,296*scale)/scale
            y=np.arange((band*96+26)*scale,(band*96+70)*scale)
            target=(truth2 if scale==2 else truth)[y][:,np.arange(48*scale,296*scale)]
            actual=img[y][:,np.arange(48*scale,296*scale),:].mean(axis=2)
            design=np.stack([np.ones_like(x),x-x.mean(),np.sin(2*np.pi*f*x),np.cos(2*np.pi*f*x)],axis=1)
            gt=np.linalg.lstsq(design,target.mean(axis=0),rcond=None)[0]
            fit=np.linalg.lstsq(design,actual.mean(axis=0),rcond=None)[0]
            spectral[name]={'amplitudeRatio':float(np.linalg.norm(fit[-2:])/np.linalg.norm(gt[-2:])),
                'phaseErrorRadians':float(np.arctan2(np.sin(np.arctan2(fit[-1],fit[-2])-np.arctan2(gt[-1],gt[-2])),
                    np.cos(np.arctan2(fit[-1],fit[-2])-np.arctan2(gt[-1],gt[-2]))))}
        row['spectral'][str(f)]=spectral
    fields=list(csv.DictReader((root/'alignment.csv').open()))
    errors=[]
    for v in fields:
        # Exclude border tiles and the deliberately moving object / flat area.
        tile=int(v['tile']); tx=tile%8;ty=tile//8
        if tx not in [1,2,3,4] or ty not in [1,2,3,4]:continue
        dx=float(v['trueDxSensor']);dy=float(v['trueDySensor'])
        errors.append([np.hypot(2*float(v['dxProxy'])-dx,2*float(v['dyProxy'])-dy),
            np.hypot(2*(float(v['dxProxy'])+float(v['fracDxProxy']))-dx,
                     2*(float(v['dyProxy'])+float(v['fracDyProxy']))-dy)])
    errors=np.array(errors)
    row['alignmentSensorPixels']={label:{'mean':float(errors[:,i].mean()),'median':float(np.median(errors[:,i])),
        'p95':float(np.percentile(errors[:,i],95))} for i,label in enumerate(['integer','currentVFit'])}
    row['gate']=dict(line.split('=',1) for line in (root/'summary.properties').read_text().splitlines() if '=' in line)
    metrics.append(row)
    if root.name in ['diverse-low','static-high','moving-object']:
        panel=Image.new('RGB',(5*256,284),(10,10,12));draw=ImageDraw.Draw(panel)
        for i,name in enumerate(['single','native-baseline','native-lanczos2x','sr-estimated','sr-known']):
            im=images[name];scale=im.shape[1]//w
            crop=im[96*scale:224*scale,64*scale:192*scale]
            display=np.where(crop<=.0031308,12.92*crop,1.055*np.maximum(crop,0)**(1/2.4)-.055)
            pic=Image.fromarray(np.uint8(np.clip(display*255+.5,0,255))).resize((256,256),Image.Resampling.NEAREST)
            panel.paste(pic,(i*256,28));draw.text((i*256+3,3),root.name+' '+name,fill='white')
        montage.append(panel)
(a.outputs/'metrics.json').write_text(json.dumps(metrics,indent=2)+'\n')
if montage:
    image=Image.new('RGB',(1280,284*len(montage)))
    for i,panel in enumerate(montage):image.paste(panel,(0,i*284))
    image.save(a.outputs/'comparison.png')
for r in metrics:
    print(r['case'],'gate',r['gate']['sr-estimated.preGate'],'detailRMSE',
          {k:round(v['rmse'],5) for k,v in r['regions']['detail'].items()},
          'motion',r['alignmentSensorPixels']['currentVFit'])
