from PIL import Image
import numpy as np
BEFORE="docs/verification/2026-10-24-figma-ui-alignment/app-screens/"
AFTER="docs/verification/2026-10-24-ui-theme-fix/after/"
D=2.625
def runs(path,y,color=(255,255,255),tol=0,minpx=200):
    a=np.asarray(Image.open(path).convert("RGB")).astype(int)
    row=a[y]; m=(np.abs(row-np.array(color)).max(axis=1)<=tol)
    xs=np.where(m)[0]
    if len(xs)==0: return []
    out=[];s=xs[0];p=xs[0]
    for x in xs[1:]:
        if x!=p+1: out.append((s,p)); s=x
        p=x
    out.append((s,p))
    return [(round(s/D,1),round((p-s+1)/D,1)) for s,p in out if p-s+1>=minpx]
for name in ["reminder-settings.png","reminder-settings-custom.png","export-screen.png","local-backup.png","security-settings.png"]:
    b=np.asarray(Image.open(BEFORE+name).convert("RGB")); a=np.asarray(Image.open(AFTER+name).convert("RGB"))
    print("==",name)
    for y in [800,1400,2000]:
        print(f"  before y={y} white runs(px x0,width dp):",runs(BEFORE+name,y))
        print(f"  after  y={y} white runs(px x0,width dp):",runs(AFTER+name,y))
    # 定点采样：卡片内安全点
    pts=[(540,800),(540,1400),(900,800),(200,800)]
    print("  before samples:",[("#%02X%02X%02X"%tuple(b[y,x])) for x,y in pts])
    print("  after  samples:",[("#%02X%02X%02X"%tuple(a[y,x])) for x,y in pts])
