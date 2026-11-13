from PIL import Image
import numpy as np, os
BEFORE="docs/verification/2026-10-24-figma-ui-alignment/app-screens/"
AFTER="docs/verification/2026-10-24-ui-theme-fix/after/"
def cnt(path,hexv,tol):
    a=np.asarray(Image.open(path).convert("RGB")).astype(int)
    c=np.array([int(hexv[i:i+2],16) for i in (0,2,4)])
    return int((np.abs(a-c).max(axis=2)<=tol).sum())
print("near-white #FFFBFF tol2 before/after (only files with any):")
for f in sorted(os.listdir(AFTER)):
    if not f.endswith(".png"): continue
    b=cnt(BEFORE+f,"FFFBFF",2); a=cnt(AFTER+f,"FFFBFF",2)
    if a or b: print(f"  {f}: before={b} after={a}")
def lum(rgb):
    c=[v/255 for v in rgb]
    c=[(v/12.92 if v<=0.04045 else ((v+0.055)/1.055)**2.4) for v in c]
    return 0.2126*c[0]+0.7152*c[1]+0.0722*c[2]
def contrast(h1,h2):
    l1,l2=lum([int(h1[i:i+2],16) for i in (0,2,4)]),lum([int(h2[i:i+2],16) for i in (0,2,4)])
    hi,lo=max(l1,l2),min(l1,l2); return round((hi+0.05)/(lo+0.05),2)
print("contrast Ink#223C32 on Soft#E7F0E6:",contrast("223C32","E7F0E6"))
print("contrast Outline#DDE4DA on Background#F7F8F3:",contrast("DDE4DA","F7F8F3"))
print("contrast Ink#223C32 on White:",contrast("223C32","FFFFFF"))
