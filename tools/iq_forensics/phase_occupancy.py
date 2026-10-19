#!/usr/bin/env python3
"""Describe original estimated phase occupancy; never a shipping eligibility rule.

Modulo-one sensor phase and modulo-two CFA-lattice phase are distinct. Saved
alignment fields are estimator outputs, not ground truth on real photographs.
Histograms have a configurable descriptive resolution; no threshold is fitted.
"""
import argparse,csv,json,math
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw

parser=argparse.ArgumentParser()
parser.add_argument('replay',type=Path)
parser.add_argument('output',type=Path)
parser.add_argument('--bins',type=int,default=8)
a=parser.parse_args()
if not 2<=a.bins<=64:parser.error('bins must be between 2 and 64')
a.output.mkdir(parents=True,exist_ok=True)
records=[];panels=[]

def stats(phase,period):
    values=np.mod(phase,period)
    bins=np.minimum((values/period*a.bins).astype(int),a.bins-1)
    histogram=np.zeros((a.bins,a.bins),dtype=int)
    np.add.at(histogram,(bins[:,1],bins[:,0]),1)
    prob=histogram[histogram>0]/len(values)
    resultant=np.abs(np.exp(2j*np.pi*values/period).mean(axis=0))
    return {'samples':len(values),'occupiedBins':int((histogram>0).sum()),
            'entropyBits':float(-(prob*np.log2(prob)).sum()),
            'circularResultantXY':resultant.tolist(),'histogram':histogram.tolist()}

for file in sorted(a.replay.glob('*/alignment.csv')):
    rows=list(csv.DictReader(file.open()));by_tile={};known={}
    for r in rows:
        t=int(r['tile']);f=int(r['frame'])
        dx=2*(float(r['dxProxy'])+float(r['fracDxProxy']))
        dy=2*(float(r['dyProxy'])+float(r['fracDyProxy']))
        by_tile.setdefault(t,{0:(0.,0.)})[f]=(dx,dy)
        if 'trueDxSensor' in r:
            known.setdefault(t,{0:(0.,0.)})[f]=(float(r['trueDxSensor']),float(r['trueDySensor']))
    record={'case':file.parent.name,'binsPerAxis':a.bins,'coordinateUnits':'sensor pixels',
            'phaseSource':'original estimated fractions before flat-tile borrowing',
            'referenceIncludedPerTile':True,'tiles':[]}
    for label,source in [('estimated',by_tile),('knownSynthetic',known)]:
        if not source:continue
        pooled=np.array([v for t in sorted(source) for _,v in sorted(source[t].items())])
        record[label]={'moduloOne':stats(pooled,1.),'moduloTwoCfa':stats(pooled,2.)}
    for t in sorted(by_tile):
        values=np.array([v for _,v in sorted(by_tile[t].items())])
        tile={'tile':t,'estimatedModuloOne':stats(values,1.),'estimatedModuloTwoCfa':stats(values,2.)}
        if t in known:
            truth=np.array([v for _,v in sorted(known[t].items())])
            tile['knownModuloOne']=stats(truth,1.);tile['knownModuloTwoCfa']=stats(truth,2.)
            tile['meanDisplacementError']=float(np.linalg.norm(values-truth,axis=1).mean())
        record['tiles'].append(tile)
    for period,key in [(1,'moduloOne'),(2,'moduloTwoCfa')]:
        panel=Image.new('RGB',(520,302),(10,10,12));d=ImageDraw.Draw(panel)
        d.text((8,5),f'{record["case"]} sensor displacement modulo {period}',fill='white')
        d.text((8,21),'Descriptive histogram; not an SR acceptance threshold',fill=(160,165,181))
        for column,label in enumerate(['estimated','knownSynthetic']):
            if label not in record:continue
            st=record[label][key];grid=np.array(st['histogram']);peak=max(1,int(grid.max()))
            d.text((8+column*260,39),label+f' ({st["occupiedBins"]}/{a.bins*a.bins} bins)',fill='white')
            for y in range(a.bins):
                for x in range(a.bins):
                    shade=int(235*np.sqrt(grid[y,x]/peak));cell=224/a.bins
                    box=(8+column*260+x*cell,60+y*cell,8+column*260+(x+1)*cell-1,60+(y+1)*cell-1)
                    d.rectangle(box,fill=(shade,shade,shade))
        panels.append(panel)
    records.append(record)
(a.output/'metrics.json').write_text(json.dumps(records,indent=2)+'\n')
with (a.output/'tiles.csv').open('w') as f:
    writer=csv.writer(f);writer.writerow(['case','tile','frames','estimatedModuloOneBins','estimatedModuloTwoBins','knownModuloOneBins','knownModuloTwoBins','meanSensorDisplacementError'])
    for r in records:
        for t in r['tiles']:
            writer.writerow([r['case'],t['tile'],t['estimatedModuloOne']['samples'],t['estimatedModuloOne']['occupiedBins'],t['estimatedModuloTwoCfa']['occupiedBins'],t.get('knownModuloOne',{}).get('occupiedBins',''),t.get('knownModuloTwoCfa',{}).get('occupiedBins',''),t.get('meanDisplacementError','')])
if panels:
    montage=Image.new('RGB',(1040,302*math.ceil(len(panels)/2)),(10,10,12))
    for i,panel in enumerate(panels):montage.paste(panel,((i%2)*520,(i//2)*302))
    montage.save(a.output/'phase-histograms.png')
for r in records:
    print(r['case'],'estimated pooled bins modulo1/2:',r['estimated']['moduloOne']['occupiedBins'],r['estimated']['moduloTwoCfa']['occupiedBins'], 'local modulo1 range',min(t['estimatedModuloOne']['occupiedBins'] for t in r['tiles']),max(t['estimatedModuloOne']['occupiedBins'] for t in r['tiles']))
