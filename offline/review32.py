"""Post-session quality review only; no live input or game interaction."""
import argparse,csv,json,math,statistics
from collections import defaultdict,Counter

def point(r):
    try:
        x=100*float(r['x'])/float(r['width']); y=100*float(r['y'])/float(r['height']); t=int(r['image_timestamp_ns'])/1e6
        return (t,x,y) if all(map(math.isfinite,(t,x,y))) and 0<=x<=100 and 0<=y<=100 else None
    except (ValueError,KeyError,ZeroDivisionError,TypeError): return None

def segments(rows):
    buf=[]
    for r in rows:
        p=point(r)
        if p is None: continue
        if buf and not 0<p[0]-buf[-1][0]<=70:
            yield buf; buf=[]
        buf.append(p)
    if buf: yield buf

def review(points,left,right,line,margin=1):
    result={'classification':'UNCERTAIN','reason':'no observed crossing','samples':len(points),'gap_ms':'','timing_uncertainty_ms':'','crossing_ms':'','x_percent':'','curve_residual_percent':''}
    if len(points)<3:
        result['reason']='insufficient samples'; return result
    # Retrospective curvature diagnostic: middle point deviation from its neighbors.
    residual=[]
    for a,b,c in zip(points,points[1:],points[2:]):
        f=(b[0]-a[0])/(c[0]-a[0]); residual.append(math.hypot(b[1]-a[1]-f*(c[1]-a[1]),b[2]-a[2]-f*(c[2]-a[2])))
    result['curve_residual_percent']=round(statistics.median(residual),3)
    crossings=[(a,b) for a,b in zip(points,points[1:]) if a[2]<line<=b[2]]
    if len(crossings)!=1:
        if crossings: result['reason']='multiple crossings'
        return result
    a,b=crossings[0]; gap=b[0]-a[0]; f=(line-a[2])/(b[2]-a[2]); x=a[1]+f*(b[1]-a[1]); uncertainty=max(f,1-f)*gap
    result.update(gap_ms=round(gap,2),timing_uncertainty_ms=round(uncertainty,2),crossing_ms=round(a[0]+f*gap,2),x_percent=round(x,3))
    if max(residual)>max(3,4*statistics.median(residual)):
        result['reason']='trajectory discontinuity'; return result
    if min(abs(x-left),abs(x-right))<=margin:
        result['reason']='boundary ambiguity'; return result
    result.update(classification='IN_ZONE' if left<x<right else 'OUTSIDE',reason='geometric review only'); return result

def main():
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('csv'); p.add_argument('--left',type=float,required=True); p.add_argument('--right',type=float,required=True); p.add_argument('--line',type=float,required=True); p.add_argument('--margin',type=float,default=1); p.add_argument('--output',default='review32.csv'); a=p.parse_args()
    if not 0<=a.left<a.right<=100 or not 0<=a.line<=100 or not 0<=a.margin<=100: p.error('invalid percentage coordinates')
    groups=defaultdict(list)
    with open(a.csv,encoding='utf-8-sig',newline='') as f:
        for r in csv.DictReader(f): groups[(r.get('session',''),r.get('side',''))].append(r)
    results=[]; stats=defaultdict(Counter)
    for (session,side),rows in groups.items():
        for i,pts in enumerate(segments(rows),1):
            r=review(pts,a.left,a.right,a.line,a.margin); results.append(dict(session=session,side=side,segment=i,**r)); stats[side][r['classification']]+=1
    fields=['session','side','segment','classification','reason','samples','gap_ms','timing_uncertainty_ms','crossing_ms','x_percent','curve_residual_percent']
    with open(a.output,'w',encoding='utf-8-sig',newline='') as f:
        w=csv.DictWriter(f,fieldnames=fields); w.writeheader(); w.writerows(results)
    print(json.dumps(stats,ensure_ascii=False,indent=2))
if __name__=='__main__': main()
