"""Shared pieces for the look-option scenes: ground patch, lantern post for scale, helpers."""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.join(os.path.dirname(HERE), "tools")
sys.path.insert(0, TOOLS)
from voxel import Structure  # noqa: E402,F401

DESIGN = os.path.dirname(HERE)


def h01(x, y, z, salt=0):
    """Stable 0..1 hash of a position (deterministic 'random' choices)."""
    n = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791) ^ (salt * 2654435761)
    n = (n ^ (n >> 13)) * 1274126177
    return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0


def ground_patch(s, r=7, plaza=5.5, snow=True):
    """Square patch -r..r: grass (snow-covered) round a paved square of radius `plaza`."""
    for x in range(-r, r + 1):
        for z in range(-r, r + 1):
            d = math.hypot(x, z)
            s.set(x, -1, z, "minecraft:dirt")
            if d <= plaza:
                k = h01(x, 0, z, 1)
                blk = ("minecraft:stone_bricks" if k < 0.45 else "minecraft:cobblestone" if k < 0.7
                       else "minecraft:andesite" if k < 0.9 else "minecraft:cracked_stone_bricks")
                s.set(x, 0, z, blk)
                if snow and d > plaza - 1.2 and h01(x, 1, z, 2) < 0.55:
                    s.set(x, 1, z, "minecraft:snow[layers=1]")
            else:
                s.set(x, 0, z, "minecraft:grass_block")
                if snow:
                    s.set(x, 1, z, "minecraft:snow[layers=%d]" % (2 if h01(x, 1, z, 4) < 0.4 else 1))


def lantern_post(s, x, z, y=1):
    """Door-height scale reference: two spruce fences with a lantern on top (top of fence = 2 blocks)."""
    s.set(x, y - 1, z, "minecraft:stone_bricks")
    s.set(x, y, z, None)
    s.set(x, y, z, "minecraft:spruce_fence")
    s.set(x, y + 1, z, "minecraft:spruce_fence")
    s.set(x, y + 2, z, "minecraft:lantern[hanging=false]")


def disc_positions(r):
    rr = int(math.ceil(r))
    return [(dx, dz) for dx in range(-rr, rr + 1) for dz in range(-rr, rr + 1) if dx * dx + dz * dz <= r * r + 1e-6]

