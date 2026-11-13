from PIL import Image
import os
for d in ["docs/verification/2026-10-24-ui-theme-fix/after","docs/verification/2026-10-24-ui-theme-fix/after-supplementary"]:
    print("==",d)
    for f in sorted(os.listdir(d)):
        if f.endswith(".png"):
            im=Image.open(os.path.join(d,f))
            print(f"  {f}: {im.size[0]}x{im.size[1]} {os.path.getsize(os.path.join(d,f))}B")
