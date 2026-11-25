"""Post-session analysis of exported LineTracker CSV. No game interaction."""
import csv, argparse, collections

def analyze(rows, left, right, line):
    prev=None
    for r in rows:
        try:
            x=float(r['x'])/float(r['width'])*100
            y=float(r['y'])/float(r['height'])*100
            t=int(r['image_timestamp_ns'])/1e6
        except (ValueError, KeyError, ZeroDivisionError):
            continue
        if prev:
            px,py,pt=prev
            if 0<t-pt<=70 and py<line<=y:
                f=(line-py)/(y-py)
                cx=px+f*(x-px)
                yield {'frame':r['frame'],'crossing_ms':round(pt+f*(t-pt),2),'x_percent':round(cx,3),'classification':'IN_ZONE' if left<=cx<=right else 'OUTSIDE','sample_gap_ms':round(t-pt,2)}
        prev=x,y,t

def main():
    p=argparse.ArgumentParser(); p.add_argument('csv'); p.add_argument('--left',type=float,required=True); p.add_argument('--right',type=float,required=True); p.add_argument('--line',type=float,required=True); p.add_argument('--output',default='review.csv'); a=p.parse_args()
    if not 0<=a.left<a.right<=100 or not 0<=a.line<=100: p.error('coordinates must be percentages')
    groups=collections.defaultdict(list)
    with open(a.csv,encoding='utf-8-sig',newline='') as f:
        for r in csv.DictReader(f): groups[r['session']].append(r)
    with open(a.output,'w',newline='',encoding='utf-8-sig') as f:
        w=csv.DictWriter(f,fieldnames=['session','frame','crossing_ms','x_percent','classification','sample_gap_ms']); w.writeheader()
        for session,rows in groups.items():
            for result in analyze(rows,a.left,a.right,a.line): w.writerow(dict(session=session,**result))
if __name__=='__main__': main()
