import sys, io, json, zipfile, re, collections
from PIL import Image
D=sys.argv[1]
ids=list(json.load(open(D+'/viscolors.json')))+list(json.load(open(D+'/viscolors2.json')))
def members(z):
    out={}
    for n in z.namelist():
        low=n.lower()
        if low.endswith('.wsz') or low.endswith('.zip'):
            try:
                inner=zipfile.ZipFile(io.BytesIO(z.read(n)))
                for k,v in members(inner).items(): out.setdefault(k,v)
            except Exception: pass
        else:
            out.setdefault(low.rsplit('/',1)[-1], (z,n))
    return out
def load(m,name):
    if name not in m: return None
    z,n=m[name]
    try: return Image.open(io.BytesIO(z.read(n))).convert('RGB')
    except Exception: return None
def mode(px):
    q=collections.Counter(px).most_common(1)
    return q[0][0] if q else None
res={}
for sid in ids:
    import glob
    f=(glob.glob(f'{D}/wsz/{sid}.*')+glob.glob(f'{D}/wsz2/{sid}.*'))[0]
    m=members(zipfile.ZipFile(f))
    r={}
    main=load(m,'main.bmp')
    if main and main.size[0]>=275 and main.size[1]>=116:
        px=main.load()
        def dom(pts):
            # moda sobre colores cuantizados (agrupa el ruido/degradados) y promedio real del grupo
            g=collections.defaultdict(list)
            for p in pts: g[tuple(v//24 for v in p)].append(p)
            best=max(g.values(), key=len)
            return tuple(sum(c[i] for c in best)//len(best) for i in range(3)), len(best)/len(pts)
        edge=[px[x,y] for x in list(range(4,15))+list(range(260,271)) for y in range(18,110)]
        edge+=[px[x,y] for x in range(4,271) for y in range(104,112)]
        r['face'],_=dom(edge)
        vbg=px[30,50]
        ring=[px[x,y] for d in (1,2,3) for x in range(24-d,101+d) for y in (43-d,59+d)]
        ring+=[px[x,y] for d in (1,2,3) for y in range(43-d,60+d) for x in (24-d,100+d)]
        ring=[p for p in ring if sum(abs(a-b) for a,b in zip(p,vbg))>40] or ring
        r['frame'],_=dom(ring)
        import colorsys
        g=collections.Counter(tuple(v//32 for v in px[x,y]) for x in range(0,275) for y in range(14,116))
        tot=sum(g.values()); bestc=None; bests=0
        for k,n in g.items():
            if n/tot<0.004: continue
            c=tuple(v*32+16 for v in k); h,l,sat=colorsys.rgb_to_hls(*(v/255 for v in c))
            sc=sat*min(l,1-l)*2*(n/tot)**0.25
            if sc>bests: bests,bestc=sc,c
        r['accent']=bestc or r['face']
    t=load(m,'text.bmp')
    if t:
        tp=list(t.get_flattened_data()); bg=tp[0]
        c=collections.Counter(p for p in tp if sum(abs(a-b) for a,b in zip(p,bg))>90)
        if c: r['text']=c.most_common(1)[0][0]
        r['textbg']=bg
    if 'pledit.txt' in m:
        z,n=m['pledit.txt']; s=z.read(n).decode('latin-1')
        for k in ('normal','current','normalbg','selectedbg'):
            mm=re.search(r'^\s*'+k+r'\s*=\s*#?([0-9a-f]{6})',s,re.I|re.M)
            if mm: r['pl_'+k]=tuple(int(mm[1][i:i+2],16) for i in (0,2,4))
    res[sid]=r
json.dump(res,open(D+'/chrome.json','w'))
miss=collections.Counter(k for r in res.values() for k in ('face','frame','lcd','text','pl_normal','pl_current','pl_normalbg') if k not in r)
print(len(res),'skins; faltantes:',dict(miss))
