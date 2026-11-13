from PIL import Image
import numpy as np, os, collections
LAV={"surfaceContainerHighest":"E6E0E9","surfaceContainerHigh":"ECE6F0","surfaceContainer":"F3EDF7","surfaceContainerLow":"F7F2FA","secondaryContainer":"E8DEF8","primaryContainer":"EADDFF","tertiaryContainer":"FFD8E4","onSurfaceVariant":"49454F","outline":"938F99","outlineVariant":"CAC4D0","surfaceTint":"6750A4","inverseSurface":"322F35","inversePrimary":"D0BCFF"}
TOK={"Background":"F7F8F3","White":"FFFFFF","Soft":"E7F0E6","Outline":"DDE4DA","Primary":"356B53","Ink":"223C32","Muted":"5F7166","Danger":"A44238","Apricot":"FAEAD8","Amber":"80582C"}
def scan(d,label):
    print("==",label)
    for f in sorted(os.listdir(d)):
        if not f.endswith(".png"): continue
        a=np.asarray(Image.open(os.path.join(d,f)).convert("RGB")).astype(int)
        h={k:int((np.abs(a-np.array([int(v[i:i+2],16) for i in (0,2,4)])).max(axis=2)<=2).sum()) for k,v in LAV.items()}
        h={k:v for k,v in h.items() if v}
        tt={k:int((np.abs(a-np.array([int(v[i:i+2],16) for i in (0,2,4)])).max(axis=2)==0).sum()) for k,v in TOK.items()}
        tt={k:v for k,v in tt.items() if v}
        print(f"{f}: lavender={h if h else 'NONE'} tokens={tt}")
scan("docs/verification/2026-10-24-ui-theme-fix/after-supplementary","after-supplementary (终版运行 2026-10-24 04:38 +08)")
scan("docs/verification/2026-10-22-figma-sync/screens","2026-10-22 historical figma-sync (pre-fix, different build)")

