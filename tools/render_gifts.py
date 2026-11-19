"""A sheet of the wrapped gift and what each gift table holds, for the owner's review (chunk S7).

Reads the gift loot tables (resources/data/colony_seasons/loot_table/gifts/*.json, written by tools/gen_gifts.py),
the item icons (our own textures, and Minecraft's from the client assets jar in SEASONS_LIBS) and the item names
(our lang file and Minecraft's), and draws one PNG: the gift's icon, then a column per table with every entry, how
many and the chance per roll. Nothing in the mod reads it.

Run:  python tools/render_gifts.py <out.png>        (tools/rigpaths.sh sets SEASONS_LIBS for the client jar)
Needs Pillow.
"""
import glob
import io
import json
import os
import sys
import zipfile

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths  # noqa: E402

NS = "colony_seasons"
TABLES = [("winter_daily", "Every day, 1 Dec to 6 Jan", "one roll"),
          ("winter_festival", "Winter Festival, 24 to 26 Dec", "two rolls, then a 15 % chance of one more"),
          ("new_year", "New Year, 31 Dec and 1 Jan", "one roll of rockets, one of the daily things")]
WIDTH = 960
COLUMN = 312
ICON = 32
ROW = 40
BG = (34, 30, 38)
CARD = (48, 43, 54)
TEXT = (238, 232, 222)
DIM = (170, 162, 150)
GOLD = (240, 192, 48)


def font(size, bold=False):
    names = ["segoeuib.ttf", "arialbd.ttf", "DejaVuSans-Bold.ttf"] if bold else ["segoeui.ttf", "arial.ttf", "DejaVuSans.ttf"]
    for name in names:
        for folder in ("C:/Windows/Fonts", "/usr/share/fonts/truetype/dejavu"):
            path = os.path.join(folder, name)
            if os.path.exists(path):
                return ImageFont.truetype(path, size)
    return ImageFont.load_default()


class Assets:
    def __init__(self):
        jars = sorted(glob.glob(os.path.join(paths.LIBS, "client-*-extra.jar")))
        self.jar = zipfile.ZipFile(jars[0]) if jars else None
        self.ours = json.load(open(paths.res("assets", NS, "lang", "en_us.json"), encoding="utf-8"))
        self.theirs = {}
        if self.jar is not None:
            self.theirs = json.loads(self.jar.read("assets/minecraft/lang/en_us.json").decode("utf-8"))

    def name(self, item_id):
        ns, path = item_id.split(":")
        for key in (f"item.{ns}.{path}", f"block.{ns}.{path}"):
            if key in self.ours:
                return self.ours[key]
            if key in self.theirs:
                return self.theirs[key]
        return path.replace("_", " ").title()

    def icon(self, item_id):
        ns, path = item_id.split(":")
        if ns == NS:
            model = json.load(open(paths.res("assets", NS, "models", "item", path + ".json"), encoding="utf-8"))
            tex = model["textures"]["layer0"].split(":")[1]
            img = Image.open(paths.res("assets", NS, "textures", tex + ".png"))
        else:
            if self.jar is None:
                return None
            img = Image.open(io.BytesIO(self.jar.read(f"assets/minecraft/textures/item/{path}.png")))
        img = img.convert("RGBA")
        img = img.crop((0, 0, img.width, img.width))     # the first frame of an animated texture
        return img.resize((ICON, ICON), Image.NEAREST)


def rows(table):
    """(item id, count text, chance text, note) for every entry, pool by pool."""
    out = []
    for pool in table["pools"]:
        entries = pool["entries"]
        total = sum(e.get("weight", 1) for e in entries)
        conditional = any(c.get("condition") == "minecraft:random_chance" for c in pool.get("conditions", []))
        for e in entries:
            count = "1"
            colour = ""
            for f in e.get("functions", []):
                if f["function"] == "minecraft:set_count":
                    c = f["count"]
                    count = str(c["min"]) if c["min"] == c["max"] else f"{c['min']} to {c['max']}"
                if f["function"] == "minecraft:set_components":
                    ex = f["components"]["minecraft:fireworks"]["explosions"][0]
                    colour = ex["shape"].replace("_", " ")
            if conditional:
                chance = f"{round(100 * pool['conditions'][0]['chance'])} %"
                colour = "an extra roll"
            else:
                chance = f"{100 * e.get('weight', 1) / total:.0f} %"
            out.append((e["name"], count, chance, colour))
        out.append(None)                                    # a gap between pools
    return out[:-1]


def main(target):
    assets = Assets()
    tables = {n: json.load(open(paths.res("data", NS, "loot_table", "gifts", n + ".json"), encoding="utf-8")) for n, _, _ in TABLES}
    longest = max(len(rows(t)) for t in tables.values())
    height = 150 + 70 + longest * ROW + 30
    sheet = Image.new("RGB", (WIDTH, height), BG)
    draw = ImageDraw.Draw(sheet)
    gift = Image.open(paths.res("assets", NS, "textures", "item", "gift.png")).convert("RGBA").resize((112, 112), Image.NEAREST)
    sheet.paste(gift, (24, 20), gift)
    draw.text((156, 30), "Wrapped Gift", font=font(30, True), fill=TEXT)
    draw.text((156, 72), "One a player per real day from the Decorator's pile at the tree.", font=font(17), fill=DIM)
    draw.text((156, 96), "Right-click opens it into one roll of its loot table (pack makers can replace the tables).", font=font(17), fill=DIM)
    for i, (name, when, how) in enumerate(TABLES):
        x = 12 + i * (COLUMN + 4)
        y = 150
        draw.rectangle((x, y, x + COLUMN - 4, height - 14), fill=CARD)
        draw.text((x + 12, y + 10), f"gifts/{name}", font=font(18, True), fill=GOLD)
        draw.text((x + 12, y + 34), when, font=font(14), fill=TEXT)
        draw.text((x + 12, y + 52), how, font=font(13), fill=DIM)
        y += 78
        for row in rows(tables[name]):
            if row is None:
                draw.line((x + 12, y + 4, x + COLUMN - 16, y + 4), fill=(80, 74, 86), width=1)
                y += 10
                continue
            item, count, chance, note = row
            icon = assets.icon(item)
            if icon is not None:
                sheet.paste(icon, (x + 10, y), icon)
            label = assets.name(item) + (f" ({note})" if note else "")
            draw.text((x + 50, y + 1), label, font=font(14), fill=TEXT)
            draw.text((x + 50, y + 19), f"x {count}", font=font(12), fill=DIM)
            draw.text((x + COLUMN - 70, y + 8), chance, font=font(14, True), fill=GOLD)
            y += ROW
    sheet.save(target, optimize=True)
    print(f"wrote {target} ({sheet.width}x{sheet.height}, {os.path.getsize(target)} bytes)")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else paths.out("gifts.png"))
