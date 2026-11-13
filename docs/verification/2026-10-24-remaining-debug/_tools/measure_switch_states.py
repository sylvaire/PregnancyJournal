import json
from PIL import Image
from collections import Counter
p=r"docs\verification\2026-10-24-remaining-debug\screenshots\switch-states.png"
im=Image.open(p).convert("RGB"); w,h=im.size; pix=im.load()
# scan rows for switch track colors by locating non-background clusters in each labeled band
rows=[]
for y in range(h):
    cnt=Counter("#%02X%02X%02X"%pix[x,y] for x in range(0,w,2))
    top=[c for c in cnt.most_common(4)]
    if any(c for c,n in top if c not in ("#F7F8F3","#FFFFFF","#E7F0E6")) and y%40<2:
        rows.append((y,top))
print("size",w,h)
print("sample bands", rows[:12])
print("top colors", Counter("#%02X%02X%02X"%pix[x,y] for y in range(h) for x in range(0,w,3)).most_common(12))
