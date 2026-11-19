"""Contact sheets: python make_sheet.py tree|hut|wreath|string  -> design/sheet-<set>.png

Variants side by side, a plain label under each, light neutral background, at most 2000 px wide.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
DESIGN = os.path.dirname(HERE)
LABELS = {
    "tree": {"A": "Village spruce", "B": "Grand tiered tree", "C": "Lantern pole"},
    "hut": {"A": "Workshop with shopfront", "B": "Steep-roof cottage", "C": "Storehouse with hoist"},
    "wreath": {"A": "Classic", "B": "Frosted", "C": "Rustic"},
    "string": {"A": "Evergreen garland", "B": "Lantern string", "C": "Fairy lights"},
}


def font(size):
    for f in ("C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/arial.ttf"):
        if os.path.exists(f):
            return ImageFont.truetype(f, size)
    return ImageFont.load_default()


def sheet(which, max_w=2000, gap=16, label_h=64):
    labels = LABELS[which]
    ims = [Image.open(os.path.join(DESIGN, f"{which}-{v}.png")).convert("RGB") for v in labels]
    n = len(ims)
    cell_w = (max_w - gap * (n + 1)) // n
    scaled = [im.resize((cell_w, round(im.height * cell_w / im.width)), Image.LANCZOS) for im in ims]
    cell_h = max(im.height for im in scaled)
    W = gap * (n + 1) + cell_w * n
    H = gap + cell_h + label_h
    out = Image.new("RGB", (W, H), (236, 236, 232))
    d = ImageDraw.Draw(out)
    f = font(30)
    for i, (v, im) in enumerate(zip(labels, scaled)):
        x = gap + i * (cell_w + gap)
        out.paste(im, (x, gap))
        text = f"{v}  {labels[v]}"
        tw = d.textlength(text, font=f)
        d.text((x + (cell_w - tw) / 2, gap + cell_h + 14), text, fill=(34, 34, 34), font=f)
    out.save(os.path.join(DESIGN, f"sheet-{which}.png"))
    print("sheet", which, out.size)


if __name__ == "__main__":
    for w in sys.argv[1:]:
        sheet(w)
