from PIL import Image
from collections import Counter
import os
shots=r"docs\verification\2026-10-24-device-visual-round16\shots"
for name in ["01-today.png","05-timeline.png","20-today-dark.png","21-timeline-dark.png","11-profile.png","12-cloud.png"]:
    im=Image.open(os.path.join(shots,name)).convert("RGB")
    c=Counter(im.getdata())
    top=[("#%02X%02X%02X"%k,v) for k,v in c.most_common(6)]
    print(name, top)
