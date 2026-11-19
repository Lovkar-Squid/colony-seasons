# Colony Seasons - look options (design renders)

Pictures for choosing by eye. Nothing here is built into the mod yet. Each picture can be turned
into the real thing later: the voxel builds are Python, and the custom blocks are already real
Minecraft blockstate, model and 16x16 texture files.

## Files

| File | What it is |
|---|---|
| `tree-A/B/C.png` | Set 1, the square's tree: A Village spruce, B Grand tiered tree, C Lantern pole (1300x1400) |
| `hut-A/B/C.png` | Set 2, Decorator's hut: A Workshop with shopfront, B Steep-roof cottage, C Storehouse with hoist (1300x1300) |
| `wreath-A/B/C.png` | Set 3, wreath above an oak door on a 5x4 colony wall: A Classic, B Frosted, C Rustic (1300x1100) |
| `string-A/B/C.png` | Set 4, five wall strings in a row under the eave: A Evergreen garland, B Lantern string, C Fairy lights (1300x1100) |
| `sheet-tree/hut/wreath/string.png` | One contact sheet per set, variants side by side with a label (1999 px wide) |
| `wreath-*-texture-x16.png`, `string-*-texture-x16.png` | The bare 16x16 texture at 16x zoom (256 px) |
| `src/assets/colonyseasons/` | Real resource-pack files: `blockstates/`, `models/block/`, `textures/block/` (all 16x16) |
| `src/make_textures.py` | Draws every custom texture (character grids, small hand-picked palettes) and writes the models, blockstates and 16x zooms |
| `src/trees.py`, `src/huts.py`, `src/walls.py` | The voxel builds (`voxel.Structure`, x east, y up, z south, ground top at y=0) |
| `src/common.py` | Ground patch, scale lantern post, helpers |
| `src/render_set.py`, `src/make_sheet.py`, `src/blocklist.py` | Render a set, make a contact sheet, print the block lists below |
| `tools/mcmodel.py` | Resolves vanilla 1.21.1 blockstates and models from the client jar (and the custom ones from `src/assets`) into textured quads |
| `tools/render_scene.py` | Blender side: builds the meshes and renders them |
| `tools/driver.py`, `tools/check_blocks.py`, `tools/voxel.py` | Glue code; check of every block id and property; copy of Voyager's voxel.py (unchanged) |

## Renderer

These are real renders with Blender 5.1 (Cycles, run headless), using the real textures from the
1.21.1 client jar. `mcmodel.py` bakes blocks the way the game does: blockstate variants and
multipart, model parents, per-face UV and UV rotation, element rotation, x/y rotation with uvlock,
cullface, tint (plains grass/foliage, spruce `#619961`), and fence/wall/pane connections and stair
corner shapes worked out the same way as vanilla. Each face is shown unlit, multiplied by vanilla's
face shade (top 1.0, N/S 0.8, E/W 0.6, bottom 0.5) and smooth-lighting AO, so the result looks like
the game in daylight with Fancy leaves. There are no sun shadows, no bloom, no depth of field and no
glow. Lights look the same as in daytime play. Block entities have no JSON model and would show as
nothing, so the builds avoid them: barrels stand in for chests, and the bunting replaces banners.
Every set uses one shared camera and the same lighting.

## Re-render

```
set MC_JAR=D:\Claude\voyager-libs\client-1.21.1-20240808.144430-extra.jar
set BLENDER=C:\Program Files\Blender Foundation\Blender 5.1\blender.exe
set CS_SCRATCH=<any temp folder>
cd design\src
python make_textures.py
python render_set.py tree          (or hut / wreath / string; add A B C to pick variants)
python make_sheet.py tree hut wreath string
python ..\tools\check_blocks.py    (every id and property is valid: prints ALL OK)
python blocklist.py
```

## Custom blocks (namespace `colonyseasons`)

These were named in the brief. Textures and models are in `src/assets`:
- `ornament_leaves[style=mixed|baubles|lights|garland]`: a spruce-leaf cube. Its base is vanilla
  spruce leaves with the tint baked in, plus baubles and lights. `mixed` picks one of three textures
  at random per position, the same way vanilla uses weighted variants.
- `tree_star[size=small|large]`: a gold 5-point star built as two crossed extrusions, 2 px thick.
  The large one is 24 px across and spans 2 blocks; its texture is a 32 px drawing split into four
  16x16 files.
- `gift_pile[facing]`: four wrapped boxes on one floor block (red, green and blue paper).
- `wreath[facing,style=classic|frosted|rustic]`: a 1 px plane against the wall, with a 1 px proud
  relief on the bow knot, the bell or the top orange slice.
- `string_garland`, `string_lanterns[variant=0|1|2]` (three colour orders, cycled along a wall) and
  `string_fairy_lights`: wall strings with `[facing]` that tile horizontally. Their content starts
  2 px below the top of the block, so an eave does not hide it.

These were invented beyond the brief:
- `ornament_leaves[style=garland]`: gold tinsel swag leaves for B's spiral garland.
- `tree_star[size=large]`: the bigger star for B.
- `string_bunting[facing,variant=0|1]`: triangle pennants (red/ochre and green/blue) for the
  cottage's eaves, used instead of banners.

## Block lists (the piece only, without the snow and grass patch or the scale post)

Bounding boxes include roof overhangs and anything set outside the walls. Wall footprints are:
hut A 11x9, hut B 9x9, hut C 7x9 plus a 2-wide open lean-to (9x9). Each hut has two floors at most.
Hut block spot (left as air): A (0,1,0), B (0,1,0), C (0,1,-1). In every hut the workbench corner and
the barrels are inside.

**Tree A Village spruce**: 7x7 crown (7x8 with the gifts), 13 tall with the star.
112 spruce_leaves, 32 ornament_leaves, 11 spruce_log, 5 gift_pile, 1 tree_star (small).

**Tree B Grand tiered tree**: 9x9 crown, 11x11 with the planter, 17 tall with the star.
185 spruce_leaves, 69 podzol, 56 ornament_leaves (of which 32 garland), 36 stone_bricks,
14 spruce_log, 5 gift_pile, 4 chiseled_stone_bricks, 4 lantern, 1 tree_star (large).

**Tree C Lantern pole**: posts on a radius-7 circle (15x15), 17 tall.
60 chain, 29 spruce_fence, 25 lantern, 8 stone_bricks, 6 ornament_leaves, 6 spruce_leaves,
6 spruce_log, 4 stone_brick_slab, 4 stone_brick_stairs.

**Hut A Workshop with shopfront**: 13x13 bounding box, 16 tall.
131 spruce_planks, 130 dark_oak_stairs, 92 stone_bricks, 81 stripped_spruce_log,
80 mushroom_stem (plaster), 42 spruce_log, 28 glass_pane, 25 cobblestone, 21 andesite,
13 dark_oak_planks, 13 dark_oak_slab, 12 spruce_trapdoor, 10 barrel, 7 mossy_cobblestone,
6 polished_andesite, 5 ladder, 4 red_carpet, 3 white_carpet, 2 spruce_door (1 door), 1 wreath,
1 crafting_table, 1 loom, 1 stonecutter, 1 hay_block, 1 lantern, 1 spruce_slab, 1 spruce_stairs,
1 dirt_path.

**Hut B Steep-roof cottage**: 11x13 bounding box, 17 tall to the campfire.
109 deepslate_tile_stairs, 109 deepslate_tiles, 88 mushroom_stem, 49 spruce_planks,
40 cobblestone, 40 dark_oak_log, 39 stripped_dark_oak_log, 24 string_bunting, 14 bricks,
14 glass_pane, 14 stone_bricks, 11 deepslate_tile_slab, 7 spruce_trapdoor, 4 andesite, 4 barrel,
3 spruce_stairs, 3 dirt_path, 2 lantern, 2 spruce_fence, 2 spruce_door, 2 snow_block, 1 campfire,
1 crafting_table, 1 loom, 1 mossy_cobblestone.

**Hut C Storehouse with hoist**: 10x13 bounding box, 15 tall.
137 spruce_planks, 102 spruce_stairs, 69 oak_planks, 69 stone_bricks, 40 stripped_spruce_log,
36 spruce_log, 20 cobblestone, 19 andesite, 12 barrel, 12 glass_pane, 12 spruce_slab,
8 mossy_cobblestone, 8 polished_andesite, 6 spruce_door (double door plus the loft door),
6 spruce_fence, 6 dirt_path, 5 chain, 3 ornament_leaves, 2 string_garland, 1 gift_pile,
1 crafting_table, 1 loom, 1 lantern.

## Notes for building the real thing

- Lanterns only hang from blocks that hold them in vanilla: chains, fences or logs.
- The star models are item-style extrusions with one element per pixel row: 34 in the small star and 96 in the large one. They
  are fine for a look test, but should be merged into fewer boxes before release.
- The ornament leaves texture is pre-tinted, so the block must have no tintindex. In the game the
  lights will not glow unless the light pixels get their own emissive element.

