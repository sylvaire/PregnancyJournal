from PIL import Image
import numpy as np, collections, json
BEFORE="docs/verification/2026-10-24-figma-ui-alignment/app-screens/"
AFTER="docs/verification/2026-10-24-ui-theme-fix/after/"
D=2.625
changed=["custom-reminder-editor.png","export-screen.png","measurement-editor.png","reminder-settings.png","timeline-source-detail.png","timeline.png"]
import os
os.makedirs("docs/verification/2026-10-24-ui-theme-fix/diffs",exist_ok=True)
for name in changed:
    b=np.asarray(Image.open(BEFORE+name).convert("RGB")); a=np.asarray(Image.open(AFTER+name).convert("RGB"))
    diff=(np.abs(a.astype(int)-b.astype(int)).max(axis=2)>2)
    ys,xs=np.where(diff)
    x0,x1,y0,y1=max(0,xs.min()-30),min(a.shape[1],xs.max()+31),max(0,ys.min()-30),min(a.shape[0],ys.max()+31)
    # color pairs
    bp=[tuple(v) for v in b[diff]]; ap=[tuple(v) for v in a[diff]]
    pairs=collections.Counter(zip(bp,ap))
    print("==",name,"diff_px",int(diff.sum()),"crop px",(x0,y0,x1,y1),"dp",(round((x1-x0)/D,1),round((y1-y0)/D,1)))
    for (bc,ac),cnt in pairs.most_common(8):
        print("   #%02X%02X%02X -> #%02X%02X%02X  %d px"%(bc+ac+(cnt,)))
    # side by side crop, 2x
    cb=Image.open(BEFORE+name).convert("RGB").crop((x0,y0,x1,y1)).resize(((x1-x0)*2,(y1-y0)*2),Image.NEAREST)
    ca=Image.open(AFTER+name).convert("RGB").crop((x0,y0,x1,y1)).resize(((x1-x0)*2,(y1-y0)*2),Image.NEAREST)
    W=cb.width+ca.width+20; H=max(cb.height,ca.height)
    out=Image.new("RGB",(W,H),(255,0,255)); out.paste(cb,(0,0)); out.paste(ca,(cb.width+20,0))
    out.save(f"docs/verification/2026-10-24-ui-theme-fix/diffs/{name.replace('.png','')}-before-after.png")
    print("   crop ->","docs/verification/2026-10-24-ui-theme-fix/diffs/"+name.replace('.png','')+"-before-after.png")
