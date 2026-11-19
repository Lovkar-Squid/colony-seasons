"""The wrapped gift item (chunk S7, festival/Gifts.java): a red box with a gold ribbon and bow, in the gift pile's
paper and ribbon colours (floor.py gift_pile), drawn as a 16x16 item sprite with a flat item model.

Written by make_all.py with the other textures; nothing is hand-edited.
"""
from common import ITEM_TEX, from_grid, item_generated, ref, save_tex

PAL = {
    "d": "#6a1820", "r": "#b02a2e", "l": "#d8504a",      # paper: dark, mid, light (gift_pile's red box)
    "o": "#a8701c", "y": "#e9b83a", "Y": "#fff0a0",      # ribbon: dark, mid, light
}

GIFT = [
    "................",
    "....yyy..yyy....",
    "...yYoyyyyoYy...",
    "...yooyYYyooy...",
    "....yyyooyyy....",
    "..lllllYYlllll..",
    ".drrrrryyrrrrrd.",
    ".ddddddyodddddd.",
    "..lrrrrYyrrrrd..",
    "..lrrrrYyrrrrd..",
    "..lrrrrYyrrrrd..",
    "..lrrrryyrrrrd..",
    "..lrrrryyrrrrd..",
    "..lrrrryorrrrd..",
    "..dddddooddddd..",
    "................",
]


def generate():
    save_tex(from_grid(GIFT, PAL), "gift", ITEM_TEX)
    item_generated("gift", ref("gift", "item"))
    return {"gift": 1}
