"""Flat front/back paper-doll previews of a citizen skin (a copy of Voyager's preview_skin.py, for our model).

Not a render: it pastes the box faces where the model puts them, at 10x, so a skin can be looked at before it goes
into the game. One card per skin tone and gender, or all of them on one sheet:
    python tools/preview_skin.py                       -> tools/out/skin_decorator_<gender>.png (tone _b)
    python tools/preview_skin.py --sheet out.png       -> both genders, all four tones, front and back
"""
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths  # noqa: E402

RES = paths.res("assets", "minecolonies", "textures", "entity", "citizen", "default")
S = 10                       # pixels per model pixel
BG = (204, 214, 226, 255)    # a winter sky


def faces(u, v, w, h, d):
    return {"top": (u + d, v, u + d + w, v + d),
            "bottom": (u + d + w, v, u + d + 2 * w, v + d),
            "right": (u, v + d, u + d, v + d + h),
            "front": (u + d, v + d, u + d + w, v + d + h),
            "left": (u + d + w, v + d, u + 2 * d + w, v + d + h),
            "back": (u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h)}


HEAD = (0, 0, 8, 8, 8)
HAT = (32, 0, 8, 8, 8)
BODY = (16, 16, 8, 12, 4)
R_ARM, L_ARM = (40, 16, 4, 12, 4), (32, 48, 4, 12, 4)
R_LEG, L_LEG = (0, 16, 4, 12, 4), (16, 48, 4, 12, 4)
# (box, dest x, dest y) in model pixels on a 16x32 doll; the pocket sits on the apron's front at the belt
COMMON = [(R_LEG, 4, 20), (L_LEG, 8, 20), (BODY, 4, 8), (R_ARM, 0, 8), (L_ARM, 12, 8)]
EXTRA = {"decorator": {"front": [((64, 0, 3, 3, 1), 4, 14)], "back": []}}


def doll(tex, who, side):
    im = Image.new("RGBA", (16 * S, 32 * S), BG)
    parts = list(COMMON) + EXTRA.get(who, {}).get(side, [])
    parts += [(HEAD, 4, 0), (HAT, 4, 0)]
    for box, dx, dy in parts:
        rect = faces(*box)[side]
        piece = tex.crop(rect)
        if side == "back":
            piece = piece.transpose(Image.FLIP_LEFT_RIGHT)
        piece = piece.resize((piece.width * S, piece.height * S), Image.NEAREST)
        im.alpha_composite(piece, (dx * S, dy * S))
    return im.transpose(Image.FLIP_LEFT_RIGHT) if side == "back" else im


def card(who, gender, sfx):
    path = os.path.join(RES, f"{who}{gender}1{sfx}.png")
    tex = Image.open(path).convert("RGBA")
    front, back = doll(tex, who, "front"), doll(tex, who, "back")
    out = Image.new("RGBA", (front.width * 2 + 24, front.height + 16), BG)
    out.alpha_composite(front, (8, 8))
    out.alpha_composite(back, (front.width + 16, 8))
    return out


def sheet(who, out_path):
    """Both genders and all four tones, front and back of each, with a small label."""
    cards = [(g, s, card(who, g, s)) for g in ("male", "female") for s in ("_a", "_b", "_d", "_w")]
    cw, ch = cards[0][2].size
    scale = 0.7
    cw2, ch2 = int(cw * scale), int(ch * scale)
    im = Image.new("RGB", (cw2 * 4 + 40, (ch2 + 22) * 2 + 20), BG[:3])
    draw = ImageDraw.Draw(im)
    for i, (g, s, c) in enumerate(cards):
        x = 8 + (i % 4) * (cw2 + 8)
        y = 8 + (i // 4) * (ch2 + 22)
        im.paste(c.resize((cw2, ch2), Image.NEAREST).convert("RGB"), (x, y))
        draw.text((x + 4, y + ch2 + 4), f"{who} {g} {s[1:]}  front / back", fill=(40, 44, 52))
    im.save(out_path, optimize=True)
    print(out_path, im.size)


def main(args):
    if len(args) >= 2 and args[0] == "--sheet":
        sheet("decorator", args[1])
        return
    os.makedirs(paths.OUT, exist_ok=True)
    for who in args or ["decorator"]:
        for gender in ("male", "female"):
            out = paths.out(f"skin_{who}_{gender}.png")
            card(who, gender, "_b").convert("RGB").save(out)
            print(out)


if __name__ == "__main__":
    main(sys.argv[1:])
