# The storage room's look: packed and festival (design round S10g)

Written 21 Nov 2026. Design round only: pictures and this file, no mod code, the shipped blueprints in `resources/`
are unchanged. Nothing here is decided until the owner picks a variant (section 6). It extends `docs/design.md`
(sections 3.2 to 3.4 and the owner's answers of 20 Nov) and the S10e storage room (`tools/decorator_hut.storage_room`);
every [B] rule there still binds.

Marks as in `docs/design.md`: **[A]** advisory, **[V]** verify on the rig first, **[O]** the owner's choice.
Nothing in this file is [B].

## 1. What the owner asked

He looked at the S10e cutaways (`docs/renders/s10e/2026-11-20-s10e-corner-l1.png`, `-attic-l3.png`,
`-attic-l5.png`) and answered: more decoration in the storage room, and depending on the season or festival, things
hung up inside, or the pieces he does not need packed and stacked away.

So the room gets two states:

- **packed** (outside the festival window): more storage dressing than S10e, and the decorations not in use visibly
  packed away (boxes and crates with a wreath or a coil of garland showing, gifts stacked, coils on wall pegs);
- **festival** (in the decorating window): a few pieces hung up inside the Workshop itself (garland along the attic's
  back beam, a wreath on an inside wall, fairy lights or a lantern string), and the boxes and crates visibly emptier,
  because their contents are out on the colony's buildings.

## 2. The pictures

| Sheet | What it shows |
|---|---|
| `design/storage/sheet-storage-A.png` | Variant A, tidy storeroom: level 1 corner and level 5 attic, packed and festival, the level 5 exterior, a notes cell |
| `design/storage/sheet-storage-B.png` | Variant B, busy loft: the same five panels |

Made by `design/src/storage_round.py` with the design renderer (`design/tools/mcmodel.py` + `render_scene.py`,
Blender headless, daylight, the game's own block models and textures, no glow). The hut is read from the shipped
blueprints; the cutaways leave out the front wall and the roof (with the tie beam) as the S10e ones do; the exterior
camera is the design's hut camera. New blocks are look drafts written at run time (vanilla textures by reference,
the mod's own decoration textures, two small hand-drawn ones: a paper label and burlap). Racks are drawn the way
MineColonies draws them in the game: while a rack holds anything its model shows two boxes standing on its top and
more on its shelves; in the festival the racks that held decorations are empty (checked with javap, minecolonies
1.1.1368: `TileEntityRack` picks the variant from whether its inventory is empty). The pictures give those boxes the
model's default textures (bricks, sand, wool, logs); in the game MineColonies retextures them after the blocks the
rack holds most of (`TileEntityRack.refreshTextureCache`, same check), so the Decorator's racks will wear the look of
his own stock. Re-render:

```
set SEASONS_LIBS=<folder with the client assets jar and a minecolonies jar>   (as in BUILDING.md)
set BLENDER=<blender.exe>
cd design\src
python storage_round.py            (checks, renders the ten panels, makes the two sheets)
python storage_round.py --sheets   (sheets only)
```

Before it renders, the script checks every variant against section 3's rules and stops on a problem: furniture only
into cells the shipped blueprint leaves as air, strictly inside the walls, off the ladder and its exit; nothing right
behind a window; the Decorator's pieces only into air, on furniture or on a wall face with a full block behind,
never on the floor and never on top of a rack; the racks unchanged; and, with each state placed, a colonist still
reaches the hut block and every floor cell from the door (`tools/walkcheck.py`). So no outside cell changes: the
exterior panels show look A as picked.

## 3. How the two states work in the mod [A]

### 3.1 Three layers

1. **The blueprint (the Builder).** Vanilla blocks and MineColonies racks only, as in S10e, so the Builder's bill gets
   no decoration. Both variants add a little vanilla storage furniture (barrels; in B also hay, a ladder, lanterns on
   fence brackets). The racks keep their cells and their counts (2, 3, 6, 9 and 12 at levels 1 to 5). Variant B's
   crate is the one exception: our own block in the blueprint (3.5).
2. **The packed set (the Decorator).** Our own thin blocks, no collision, placed by the Decorator outside the window.
3. **The festival set (the Decorator).** The shipped wall decorations (wreath, garland, lantern string, fairy lights)
   hung inside, and the packed pieces swapped for their emptier states.

Layers 2 and 3 follow the ledger rule of `docs/design.md` 3.2 and 3.3 unchanged: a piece goes only where the world
block is air, is written to the ledger before the next one, and is removed only where the ledger lists it and the
state there is exactly the recorded one. They are free dressing: they cost no materials and drop nothing when broken
(the owner's 20 Nov rule for free decorations). They form one ledger group, the Workshop's storage room, so
`/colonyseasons decor remove`, the server-side clean-up when the hut or the Decorator is gone, and uninstalling all
treat them like every other decoration.

Where a piece may stand, in both variants:

- on top of a barrel, a crate or a hay bale, or on a wall face with a full block behind it; never on the floor (so
  nothing with collision is ever placed automatically, design 3.2, and the walk path stays clear);
- never on top of a rack: MineColonies' full rack model draws two boxes standing 6 px up into the cell above the rack
  (found while making these pictures), so a piece there would cut through them. Above a rack, only a wall piece flat
  on the wall is allowed (the rack's boxes stand at least 1 px off the wall);
- never right behind a window, so the outside looks the same at every level.

A piece that stands on something breaks (and drops nothing) when that block is gone, as a carpet does; a wall piece
needs its full block behind it, as the shipped wall decorations do.

### 3.2 The window's start and end

- **Start of the window.** The Workshop is the Decorator's first stop on the first day: he takes the packed pieces
  away (exact match) and puts the festival set into the cells that are now air and into the hang spots. A "swap"
  is always remove-then-place into air; no state is ever edited in place. A box going from full to nearly empty is
  the same block in a different state, removed and placed again.
- **During the window.** Tending (design 4) covers the Workshop like every other building: a hung piece that went
  missing is put back once a game day. The indoor lights are lit by the same rule as the outside ones.
- **End of the window.** The Workshop is his last stop of the take-down: the festival set comes off (exact match)
  and the packed set goes back, while the taken-down decorations go back into the racks (S10e), so the room and the
  racks show "packed" together.
- **Outside the window.** The packed set is placed the first time the Workshop stands finished with a Decorator
  employed, and after every upgrade (3.3).
- [O] The packed set can be fixed (always the same pieces) or follow the stock: a full box of a kind while the racks
  hold that kind, a nearly empty one when they do not. Fixed is simpler; following the stock is more honest.

### 3.3 Upgrades, repairs and the Builder

The rule of design 3.2 already says: skip a building with an open work order and take our decorations off it first.
For the Workshop that means: when a work order (upgrade or repair) opens on it, the Decorator, or the server if he is
away, removes the whole storage group before the Builder starts; when the work order is done and the building is no
longer pending construction, he places the set of the new level in the current state. The spots of the new level
come from the new blueprint (3.4), so the level 1 to 2 corner moving to the attic at level 3 needs nothing special.

If the Builder gets there first, his clearing stage sets our thin blocks in the blueprint's air cells to air (or
breaks them). Nothing is lost or doubled: the pieces are free and drop nothing, and the ledger, finding air or another
state, forgets the entry without touching the world. The room is simply bare until the Decorator dresses it again.

### 3.4 Where the spots come from: Structurize position tags

The spots are **position tags** in each of the five Workshop blueprints, not fixed offsets in our code:

- `cs_pack_<piece>` for the packed set (for example `cs_pack_box_wreath`, `cs_pack_coil_garland`, `cs_pack_sack`),
  and `cs_hang_<piece>` for the festival set (`cs_hang_garland`, `cs_hang_wreath`, `cs_hang_lights`,
  `cs_hang_lantern_string`). A spot that swaps (a box going nearly empty) carries both tags.
- Tags are data in the hut block's blueprint data, not blocks: they are not on the Builder's bill. `tools/voxel.py`
  already writes them (the Workshop's `name=` tag is one).
- MineColonies reads them with `IBuilding.getLocationsFromTag(String)` (checked with javap in minecolonies
  1.1.1368; the hut's block entity has `getWorldTagNamePosMap()`). [V] That the positions come back turned with the
  building's rotation and mirror is to be proven on the rig by the build chunk's probe before anything relies on it;
  fallback: turn the blueprint's relative offsets with the building's `getRotationMirror()` ourselves.
- A piece's facing is worked out when it is placed, not stored: a wall piece faces away from the full block next to
  it (the rule of design 3.4), a standing piece faces the room.
- `tools/build_pack.py` gets the checks this round's script makes: every tag cell is air in the blueprint, stands on
  furniture or has a full block behind it, is not on a rack, not behind a window and not on the ladder's way, and the
  walk check passes with either set placed.
- A tag cell that is not air when he comes (a player put something there) is skipped.

### 3.5 What stays in the blueprint, per variant

- **A:** barrels only: +2 at level 1 (one standing, one lying), +3 at level 5 (a stack of two in the east corner, one
  on the west stack). The bill grows by a few barrels; nothing new on it.
- **B:** hay bales (1 at level 1, 3 at level 5), a ladder leaning on the east wall (3 rungs), two lanterns on spruce
  fence brackets at level 5, and **our own crate** (1 at level 1, 3 at level 5). The crate stands on the floor and has
  collision, so it cannot be placed by the Decorator (design 3.2): it must be a blueprint block, and so it goes on the
  Builder's bill. Cost to the player: he crafts the crates (a cheap recipe, for example planks and sticks) or the
  colony does; until then the Builder waits and the request shows in the player's request list, which is the friction
  the S10e rule avoids. At level 2 the crate's cell becomes a rack; the Builder takes the crate out. What lies in a
  crate is a separate thin block of the Decorator's in the cell above, whose model reaches down into the crate, so
  the crate itself never changes state.

## 4. The two variants

| | A Tidy storeroom | B Busy loft |
|---|---|---|
| Feel | ordered: labelled boxes in rows, coils on pegs | lived in: half-open crates, sacks, hay, lanterns, a ladder |
| Blueprint (Builder) | vanilla barrels; nothing new on the bill | hay, ladder, lanterns on brackets, **our crate on the bill** |
| Packed set, level 1 / 5 | 6 / 8 pieces: labelled boxes (full) on barrels, a coil on a barrel, coils on wall pegs | 6 / 10 pieces: heaps in the crates, sacks, stacked gifts, coils on pegs with a loose end |
| Festival set, level 1 / 5 | 13 / 22 blocks: the boxes nearly empty; garland along the back beam, a wreath, fairy lights (10 / 17 hung) | 14 / 22 blocks: crates nearly empty, sacks folded, gifts down to one; lantern string on the back beam, garland on the side beams, a wreath (10 / 15 hung) |
| Racks | as shipped; full in packed, the decoration racks empty in the festival | the same |

Levels 2 to 4 follow the same rules; their spots are laid out by the build chunk (the pictures show levels 1 and 5).

## 5. What each needs, and the cost

**New blocks and models** (block only, no item and no recipe: only the Decorator places them; no loot table, they
drop nothing; a lang key each for the block name that tooltips and Jade show):

| | A | B |
|---|---|---|
| storage box (content: wreath, garland, lights, lanterns, gifts; full or nearly empty) | 1 block, 10 models | |
| coil lying on a barrel (garland, lights) | 1 block, 2 models | the same with a loose end: 1 block, 2 models |
| coil on a wall peg (garland, lights) | 1 block, 2 models | the same with a loose end: 1 block, 2 models |
| crate (blueprint block: collision, item, recipe, loot, on the bill) | | 1 block, 1 model |
| crate contents (garland, wreath, lights, gifts; full or nearly empty) | | 1 block, 8 models |
| sack (full, folded) and loose gifts (stacked, one) | | 2 blocks, 4 models |
| new textures | the paper label | burlap, rope |

All models reuse the shipped decoration textures (garland, wreath, fairy lights, lantern string, gift paper) and
vanilla planks, logs and hay by reference. Coils are eight-sided rings of four straight and four 45-degree bars.

**AI work** (both): one storeroom step for the Decorator at the window's start (unpack: swap to the festival set) and
at its end (pack: swap back), with a status line each ("Unpacking the storeroom", "Packing the storeroom away"); the
placing and the removal are the ones the colony's buildings already use (ledger group, air-only, exact match); the
work-order hook of 3.3 for his own hut. B adds nothing to the AI; the crate is the Builder's.

**Probe** (both): a rig probe that builds the Workshop at level 1, upgrades it to 5 with the hut turned (proving the
tags come back turned, the [V] of 3.4), checks that the packed set appears, runs a window start and end (the swap
both ways, the ledger clean afterwards), and opens a work order to see the set come off and go back. B also needs the
crate requested by the Builder and delivered.

**Cost in agent work** [A]:

- **A:** about one normal build chunk: the three blocks and their models (scripted, as the wall decorations were),
  the tags in `tools/decorator_hut.py` and the checks in `build_pack.py`, the storeroom step and the work-order hook,
  the probe; a builder, an auditor and the verify, no rigor-high stage.
- **B:** about one and a half to two chunks: everything in A, plus the crate as a real blueprint block (item, recipe,
  loot, lang, the request path tested with a real Builder), the crate contents drawn into the crate below, two more
  blocks and twice the models.

## 6. Recommendation and questions

**Recommendation [A]: variant A**, because it keeps the S10e rule (nothing new on the Builder's bill, nothing with
collision placed automatically) and reads clearly in both states at about half of B's cost.

If he likes B's busier feel, A can take B's vanilla touches (hay, the leaning ladder, the lanterns on fence brackets)
at no extra cost to the bill or the code; only B's crate is what makes B expensive.

Questions for the owner [O]:

1. A, B, or A with B's vanilla touches?
2. Should the packed boxes follow the real stock (empty when he has none of a kind), or always look the same?

A note on S10e as shipped (not changed here): the attic's racks stand two high, and MineColonies draws a full rack's
two boxes 6 px up into the cell above, so while the lower rack holds anything its boxes show inside the rack above,
as in any MineColonies warehouse with stacked racks.
