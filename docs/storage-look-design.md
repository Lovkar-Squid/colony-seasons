# The storage room's look: packed and festival (design round S10g)

Written 21 Nov 2026 and revised the same day after an independent review. Design round only: pictures and this
file, no mod code, the shipped blueprints in `resources/` are unchanged. Nothing here is decided until the owner picks
a variant (section 6). It extends `docs/design.md` (sections 3.2 to 3.4 and the owner's answers of 20 Nov) and the
S10e storage room (`tools/decorator_hut.storage_room`); every [B] rule there still binds.

Marks as in `docs/design.md`: **[A]** advisory, **[V]** verify on the rig first, **[O]** the owner's choice.
Nothing in this file is [B].

## 1. What the owner asked, and the scope of 0.1.0

He looked at the S10e cutaways (`docs/renders/s10e/2026-11-20-s10e-corner-l1.png`, `-attic-l3.png`,
`-attic-l5.png`) and answered: more decoration in the storage room, and depending on the season or festival, things
hung up inside, or the pieces he does not need packed and stacked away.

So the room gets two states:

- **packed** (outside the decoration window): more storage dressing than S10e, and the decorations not in use visibly
  packed away (labelled boxes and crates with a wreath, a coil of garland or a coil of lights showing, coils lying on
  barrels, gifts stacked). Nothing hangs on a wall in this state: hung things are the festival's signal.
- **festival** (in the decoration window): a few pieces hung up inside the Workshop itself (garland along the
  attic's back beam, a wreath on an inside wall, fairy lights or a lantern string), and the boxes and crates visibly
  emptier, because their contents are out on the colony's buildings.

**What "depending on the season or festival" means in 0.1.0.** 0.1.0 has one decoration window, the Winter one (1 Dec
to 6 Jan, with New Year's window inside it; design 2.1). So the room has one festival set, the winter set, and the
packed winter set stands for the rest of the year. Seasons alone change nothing in the room, just as they change
nothing in the world in 0.1.0 (design 2.2: no world change, nothing placed): a summer room looks the same as an autumn
one. The later festivals (Spring Bloom, Harvest Fair, Night of Lanterns; design 11) each bring their own decorations
and their own pair of sets; section 3.6 says how, and the tag names of 0.1.0 already leave room for them.

## 2. The pictures

| Sheet | What it shows |
|---|---|
| `design/storage/sheet-storage-A.png` | Variant A, tidy storeroom: level 1 corner and level 5 attic, packed and festival, the level 5 exterior, a notes cell |
| `design/storage/sheet-storage-B.png` | Variant B, busy loft: the same five panels |

Made by `design/src/storage_round.py` with the design renderer (`design/tools/mcmodel.py` + `render_scene.py`,
Blender headless, daylight, the game's own block models and textures, no glow). The hut is read from the shipped
blueprints; the cutaways leave out the front wall and the roof (with the tie beam) as the S10e ones do; the exterior
camera is the design's hut camera. New blocks are look drafts written at run time (vanilla textures by reference,
the mod's own decoration textures, three small hand-drawn ones: colour-coded card labels, burlap and rope).

Racks are drawn as single racks facing south, with MineColonies' full model while they hold anything: two boxes
standing on the top and more on the shelves (`TileEntityRack` picks the model by whether its inventory is empty;
javap, minecolonies 1.1.1368). In the game, neighbouring racks can join into doubles, whose boxes sit elsewhere, and
the blueprint's racks carry no facing; the rule in 3.1 keeps either layout clear. The festival panels draw the attic
racks that held the decorations as empty: that is an assumption about the stock (they are empty only while every
decoration of theirs is out), not something the mod sets. The pictures give the rack boxes the model's default
textures (bricks, sand, wool, logs); in the game MineColonies retextures them after the blocks the rack holds most of
(`TileEntityRack.refreshTextureCache`), so the Decorator's racks will wear the look of his own stock. Re-render:

```
set SEASONS_LIBS=<folder with the client assets jar and a minecolonies jar>   (as in BUILDING.md)
set BLENDER=<blender.exe>
cd design\src
python storage_round.py            (checks, renders the ten panels, makes the two sheets)
python storage_round.py --sheets   (sheets only)
```

Before it renders, the script checks every variant against section 3's rules and stops on a problem: furniture only
into cells the shipped blueprint leaves as air, strictly inside the walls, off the ladder and its exit; nothing right
behind a window; nothing at all (furniture or the Decorator's pieces) in the cell directly above a rack; the
Decorator's pieces only into air, standing on furniture or (festival only) on a wall face with a full block behind,
never on the floor, and nothing hung in the packed set; the racks unchanged; and, with each state placed, a colonist
still reaches the hut block and every floor cell from the door (`tools/walkcheck.py`). A self-test first proves that
the check catches a festival piece above a rack and a hung piece in the packed set. So no outside cell changes: the
exterior panels show look A as picked (they differ from `docs/renders/s5/2026-11-19-s5-hut-l5.png` only by about 20
scattered render-noise pixels).

## 3. How the two states work in the mod [A]

### 3.1 Three layers, and where a piece may go

1. **The blueprint (the Builder).** Vanilla blocks and MineColonies racks only, as in S10e, so the Builder's bill gets
   no decoration. Both variants add a little vanilla storage furniture (barrels; in B also hay, a ladder and a lantern
   on a fence bracket). The racks keep their cells and their counts (2, 3, 6, 9 and 12 at levels 1 to 5). Variant B's
   crate is the one exception: our own block in the blueprint (3.7).
2. **The packed set (the Decorator).** Our own thin blocks, no collision, all standing on furniture.
3. **The festival set (the Decorator).** The shipped wall decorations (wreath, garland, lantern string, fairy lights)
   hung inside, and the packed pieces swapped for their emptier states.

Layers 2 and 3 keep the placing and removal rules of `docs/design.md` 3.2 and 3.3: a piece goes only where the world
block is air, is written to the ledger before the next one, and is removed only where the ledger lists it and the
state there is exactly the recorded one. They are free dressing: they cost no materials and drop nothing when broken
(the owner's 20 Nov rule for free decorations). But they do **not** fit the ledger as it is: the packed set stands all
year, and the ledger, the take-down and the server sweep all assume that nothing is listed outside a window. Section
3.3 says what has to change.

Where a piece may go, in both variants:

- standing on top of a barrel, a crate or a hay bale; or, in the festival set only, on a wall face with a full block
  behind it, as the shipped wall decorations need. Never on the floor (so nothing with collision is ever placed
  automatically, design 3.2, and the walk path stays clear), and nothing hangs in the packed set;
- **never in the cell directly above a rack**, whatever it is: a standing piece, a flat wall piece, or blueprint
  furniture. MineColonies' full rack model draws boxes 6 px up into that cell (`blockrackfull.json`: y 16 to 22), the
  double rack's model has one that reaches the cell's edge (`blockrackfulldouble.json`, block6: z 10 to 16), and our
  wall pieces are 2 px deep (z 14 to 16), so even a piece flat on the wall can cut through them. A rack shows the full
  model as soon as it holds any item, so this cannot rest on the racks being empty in the window. (The first draft of
  this round put two festival pieces on the back beam above the upper attic racks; the rule and its check were added
  after the review.) At level 5 this leaves the back beam above the two upper racks bare in both states;
- never right behind a window, so the outside looks the same at every level.

A piece that stands on something breaks (and drops nothing) when that block is gone, as a carpet does; a wall piece
needs its full block behind it, as the shipped wall decorations do.

At levels 3 to 5 the dressing is in the attic only. The ground floor's back-east corner keeps its S10e racks (six at
level 5) with no dressing, so the hut's working floor stays as it was [A]; whether it should get some is question 3 in
section 6.

### 3.2 A target state, checked on every visit to the hut

The room has a **target state**, worked out from the calendar and the hut alone:

- while the Decorator's year is in a festival phase (`DecorPhase` DECORATING or HOSTING, or TAKING_DOWN while the
  colony's outside decorations are still up), the festival set of that festival (0.1.0: the winter set, for the
  Winter Festival's and New Year's windows alike, forced ones included);
- otherwise (OFF_SEASON), and whenever the hut's "decorate" setting is off, the packed set.

Either set is the one of the Workshop's current level (its tags, 3.5). He checks it every time he is at his hut (he
walks back there between jobs in every phase), at most once a game day unless the target changed since the last check
(the phase turned, the level or the setting changed). A check compares what the ledger lists for the room with the
target, then removes by the removal rule (exact match) every listed piece that is not in the target and places into
air every target piece that is missing. A "swap" of a box from full to nearly empty is therefore a removal and a
placing; no state is ever edited in place. A status line says so when a check changes the set ("Unpacking the
storeroom", "Packing the storeroom away").

Because the rule is a target and not a pair of one-time switches, all of these end right with no extra code: the
server down over 1 Dec or 7 Jan; `festival force` and `festival clear` in either direction (after a clear, the room
stays festive until the take-down is done, like the outside); a Decorator hired in the middle of a window (he finds
the room packed and unpacks it); a crash between removing and placing (the ledger lists what is there, the next check
places the rest); a piece a player broke (the ledger forgot it, the next check puts it back, as tending does outside).

Tying the festival set to the phase rather than to the window keeps the room and the racks in step: the room is
packed when the take-down is over, which is also when the taken-down decorations are back in the racks (S10e). In
practice the Workshop is his first stop when a window opens (he starts from his hut) and his last when the take-down
ends; that is only the order things happen in, not a rule the code needs.

- [O] The packed set can be fixed (always the same pieces) or follow the stock: a full box of a kind while the racks
  hold that kind, a nearly empty one when they do not. Fixed is simpler; following the stock is more honest.

### 3.3 The ledger: a storeroom kind (format version 2)

What the current code assumes (`decor/DecorLedger.java`, `DecorWork.java`, `DecorEvents.java`,
`ai/EntityAIWorkDecorator.java`):

- the ledger knows two owner kinds, `BUILDING` and `SQUARE`, keyed by position, and the Workshop's own outside
  decorations already use `Owner.building(hut position)`;
- `DecorWork.nextToTakeDown` returns every group that still has entries, so after a window the Decorator would take
  the room down, and the take-down would never end while the room is listed;
- the AI reads "decorations up" as `decorationsUp() || ledger.hasEntries(colony)`, and `takingDown()` and `upkeep()`
  finish only when `!hasEntries`: with the room listed all year the phase would stay TAKING_DOWN for good;
- the sweep resets the hut's `decorationsUp` only when `!ledger.hasEntries(id)`, and after a window it removes every
  group of a colony that has no Decorator (`windowOver && noDecorator`);
- the day's building budget counts every owner the Decorator starts (`DecorLedger.charge`).

So the room needs, all [A]:

1. **A third owner kind, `STOREROOM`,** with `Owner.storeroom(hut position)`: the Workshop's room is its own group,
   apart from the Workshop's outside group `Owner.building(hut position)` at the same position. The group's existing
   fields carry its state: `festival` is the set that is up (`""` for packed, else the set id, 3.6) and `level` the
   Workshop level it was laid out for. The ledger's `VERSION` goes from 1 to 2. A version 1 file reads as before (it
   has no storeroom groups). A build that knows only version 1, reading a version 2 file, already warns once and skips
   the groups of a kind it does not know (`DecorLedger.load`); their blocks do not exist in that build either (they
   come with the same version), so the world drops them on load as missing blocks and nothing is left orphaned.
2. **Two kinds of "has entries".** `hasOutdoorEntries(colony)` (BUILDING and SQUARE groups only) replaces
   `hasEntries` where it means "the festival decorations are up": the AI's phase, `takingDown()`, `upkeep()` and the
   sweep's `decorationsUp` reset. `hasEntries` (all kinds) stays where it means "anything listed at all":
   `dropColony` and the sweep's `gone()`, so a deleted colony's record is not dropped while its room is still listed.
   `buildingsDone` already counts BUILDING groups only.
3. **No take-down and no budget.** `nextToTakeDown` skips STOREROOM groups (the room changes by its target, 3.2). The
   storeroom check is never charged against the day's building budget and does not use a decorating visit.
4. **The sweep's rules for a STOREROOM group:** remove it when it is marked removing; when the Workshop is gone or busy
   (no building at the owner's position, or an open work order: `ownerGoneOrBusy`, which already works by position)
   or the colony is gone; when the window is over, the colony has no Decorator, and the group holds a festival set
   (the packed set is right outside the window and needs nobody, so it stays); and when the room's config switch is
   off (point 6). Never otherwise.
5. **Removal and commands.** `BuildingRemovedModEvent` for the Workshop queues its STOREROOM group with its BUILDING
   group. `decor list` names the kind (a lang key `...command.decor.kind.storeroom`, "Storeroom"). `decor remove
   <colony>` removes the room with everything else; the Decorator dresses it again at his next check, as he puts the
   outside decorations back during a window. Before uninstalling this is harmless: every storeroom block is our own
   and vanishes with the mod (there is no vanilla part, as there is with the tree).
6. **A switch.** Config `decor.storeroomDressing` (default true): off, he never dresses the room and the sweep takes
   any storeroom group down, so an admin who wants the room bare for good has a way.

### 3.4 Upgrades, repairs and the Builder

The rule of design 3.2 already says: skip a building with an open work order and take our decorations off it first.
For the room that means: when a work order (upgrade or repair) opens on the Workshop, the sweep removes the STOREROOM
group within its 100-tick interval, as it does for the outside groups; when the work order is done and the building
is no longer pending construction, the next check at the hut places the set of the new level in the current target
state. The spots of the new level come from the new blueprint (3.5), so the level 1 to 2 corner moving to the attic
at level 3 needs nothing special.

If the Builder gets there first, his clearing stage sets our thin blocks in the blueprint's air cells to air (or
breaks them). Nothing is lost or doubled: the pieces are free and drop nothing, and the ledger, finding air or another
state, forgets the entry without touching the world. The room is simply bare until the next check.

### 3.5 Where the spots come from: Structurize position tags

The spots are **position tags** in each of the five Workshop blueprints, not fixed offsets in our code:

- `cs_pack_<set>_<piece>` for a packed piece (for example `cs_pack_winter_box_wreath`, `cs_pack_winter_coil_garland`),
  and `cs_hang_<set>_<piece>` for a festival piece (`cs_hang_winter_garland`, `cs_hang_winter_wreath`,
  `cs_hang_winter_lights`). A spot that swaps (a box going nearly empty) carries only its `cs_pack_` tag. What a
  packed piece does in the festival follows from the piece named in its tag: a box, crate contents, a sack or gifts
  turn to the same block's emptier state; a coil is taken away (it is out on the buildings). The set id is part of
  every name (3.6).
- Tags are data in the hut block's blueprint data, not blocks: they are not on the Builder's bill. `tools/voxel.py`
  already writes them (the Workshop's `name=` tag is one).
- MineColonies reads them with `IBuilding.getLocationsFromTag(String)`, which reads the building's cached map
  `getCachedWorldTagNamePosMap()` (javap, minecolonies 1.1.1368; the hut's block entity has
  `getWorldTagNamePosMap()`). [V] Two things are to be proven on the rig by the build chunk's probe before anything
  relies on them: that the positions come back turned with the building's rotation and mirror, and that the cached map
  is refreshed after an upgrade (the new level's tags, not the old ones). Fallback for either: read the tags from the
  level's blueprint ourselves and turn the offsets with the building's `getRotationMirror()`.
- A piece's facing is worked out when it is placed, not stored: a wall piece faces away from the full block next to
  it (the rule of design 3.4), a standing piece faces the room.
- `tools/build_pack.py` gets the checks this round's script makes: every tag cell is air in the blueprint; a
  `cs_pack_` cell stands on furniture; a `cs_hang_` cell has a full block behind it; no tag cell is directly above a
  rack, behind a window or on the ladder's way; and the walk check passes with either set placed.
- A tag cell that is not air when he comes (a player put something there) is skipped.

### 3.6 Later festivals: one pair of sets each

Every set has an id, and every tag name carries it (3.5). 0.1.0 has one set, `winter`, used by the windows of both
of its festivals (the Winter Festival and New Year share the winter decorations, as they do outside). A later festival
version adds its own set id (for example `spring`, `harvest`, `lanterns`), its spots in the five blueprints, and its
line in a small map from festival id to set id next to the festival list in code. Then:

- outside every window, every set stands packed: each festival has its own boxes on the barrels;
- in one festival's window, only that festival's packed pieces turn to their festival state and its hang spots
  fill; the other festivals' boxes stay packed;
- two windows open at once (possible only by config or force) show the set of the festival the outside decorating
  uses (`DecorWork.festival()`, the first open one).

So nothing of 0.1.0 has to be renamed when a festival comes, and the room still shows only one festival at a time.

### 3.7 What stays in the blueprint, per variant

- **A:** barrels only: +3 at level 1 (two standing side by side and one lying), +4 at level 5 (a stack of two in the
  east corner, one on the west stack, one on the east side). The bill grows by a few barrels, a kind of block it
  already has at every level: no new kind of block on it.
- **B:** hay bales (1 at level 1, 3 at level 5), a ladder leaning on the east wall (3 rungs), a lantern on a spruce
  fence bracket at level 5, and **our own crate** (1 at level 1, 3 at level 5). Hay and the ladder are new kinds on
  the level 1 bill; level 5 already has all four vanilla kinds. The crate stands on the floor and has collision, so it
  cannot be placed by the Decorator (design 3.2): it must be a blueprint block, and so it goes on the Builder's bill.
  Cost to the player: he crafts the crates (a cheap recipe, for example planks and sticks) or the colony does; until
  then the Builder waits and the request shows in the player's request list, which is the friction the S10e rule
  avoids. At level 2 the crate's cell becomes a rack; the Builder takes the crate out. What lies in a crate is a
  separate thin block of the Decorator's in the cell above, whose model reaches 15 px down into the crate, so the
  crate itself never changes state. [A] That block's outline and hit shape should reach down into the crate as its
  model does (a shape may leave its cell, as a fence's collision does), so that aiming at the contents names them in
  the tooltip and Jade instead of the empty-looking cell above.

## 4. The two variants

| | A Tidy storeroom | B Busy loft |
|---|---|---|
| Feel | ordered: labelled boxes in rows, coils lying on barrels | lived in: half-open crates, sacks, hay, a lantern, a ladder |
| Blueprint (Builder) | vanilla barrels; no new kind of block on the bill | hay, ladder, a lantern on a bracket (vanilla), **our crate on the bill** |
| Packed set, level 1 / 5 | 5 / 6 pieces: labelled boxes (full) and coils lying on barrels; nothing hung | 4 / 8 pieces: heaps in the crates, sacks, stacked gifts, a coil with a loose end over the edge; nothing hung |
| Festival set, level 1 / 5 | 13 / 20 blocks: the boxes nearly empty, the coils away; garland along the back beam, a wreath, fairy lights (10 / 15 hung) | 13 / 19 blocks: crates nearly empty, sacks folded, gifts down to one, the coil away; lantern string on the back beam, garland along a side wall, a wreath (10 / 12 hung) |
| Racks | as shipped; full in packed; drawn empty in the festival where they held decorations (3.1, 2) | the same |

Levels 2 to 4 follow the same rules; their spots are laid out by the build chunk (the pictures show levels 1 and 5).

## 5. What each needs, and the cost

**New blocks and models.** Block only, no item and no recipe, and no loot table (they drop nothing); a lang key each
for the block name that tooltips and Jade show. This is unlike the decoration blocks of `docs/design.md` 3.1, which
all have an item and a crafting recipe so players can decorate by hand: these are the Decorator's room dressing,
placed only by him, and they are not meant for players (creative pick-block gives nothing). B's crate is the
exception, because the Builder needs it as an item.

| | A | B |
|---|---|---|
| storage box (content: wreath, garland, lights, lanterns, gifts; full or nearly empty) | 1 block, 10 models | |
| coil lying on a barrel, crate or hay (garland, lights) | 1 block, 2 models | the same with a loose end: 1 block, 2 models |
| crate (blueprint block: collision, item, recipe, loot, on the bill) | | 1 block, 1 model |
| crate contents (garland, wreath, lights, gifts; full or nearly empty) | | 1 block, 8 models |
| sack (full, folded) and loose gifts (stacked, one) | | 2 blocks, 4 models |
| new textures | the card labels | burlap, rope |

All models reuse the shipped decoration textures (garland, wreath, fairy lights, lantern string, gift paper) and
vanilla planks, logs and hay by reference. Coils are eight-sided rings of four straight and four 45-degree bars; a
coil of lights is a thin wire ring with its bulbs lying on it. The wall-peg coil of the first draft is gone (packed
pieces do not hang), which saves a block.

**Ledger and AI work** (both, 3.2 and 3.3): the STOREROOM kind and ledger version 2, with load tests for version 1
and version 2 files; the split of `hasEntries` at its call sites; the take-down skip; the sweep's rules; no budget
charge; the target-state check at the hut with its two status lines; the work-order and building-removed hooks; the
lang key for `decor list`; the config switch. B adds nothing here; the crate is the Builder's.

**Probe** (both): a rig probe that builds the Workshop at level 1, upgrades it to 5 with the hut turned (the two [V]
of 3.5: the tags come back turned, and the cached tag map holds the new level's tags after each upgrade), and checks:
the packed set appears; a window's start and end turn the room both ways, and after the take-down the room is packed
and the outside ledger empty while the room's group stays; a restart in the middle of a check (part removed, part
placed) ends right; `festival force` and `festival clear` both ways; a Decorator hired in the middle of a window;
`decor remove` and the room coming back; no Decorator after a window (the festival set goes, the packed set stays);
the switch off; a work order takes the room off and puts the new level's set back; a version 1 ledger file loads. B
also needs the crate requested by the Builder and delivered.

**Cost in agent work** [A]:

- **A:** about one rigor-high chunk and one normal chunk (the first estimate, one normal chunk, left out the ledger
  work). The rigor-high one is the ledger and the AI (3.3 and the check of 3.2), because it changes the take-down and
  the sweep that the `csdecorgate` probe guards; the normal one is the two blocks and their models (scripted, as the
  wall decorations were), the tags in `tools/decorator_hut.py`, the checks in `build_pack.py`, and the probe.
- **B:** everything in A, plus the crate as a real blueprint block (item, recipe, loot, lang, the request path tested
  with a real Builder), the crate contents drawn into the crate below, two more blocks and twice the models: about one
  rigor-high chunk and one and a half to two normal ones.

## 6. Recommendation and questions

**Recommendation [A]: variant A**, because it keeps the S10e rule (no decoration and no new kind of block on the
Builder's bill, nothing with collision placed automatically) and reads clearly in both states, at clearly less cost
than B (the ledger work is the same for both; B adds the crate and more blocks on top).

If he likes B's busier feel, A can take B's vanilla touches (hay, the leaning ladder, a lantern on a fence bracket)
with no new code and no block of ours. They do add cheap vanilla blocks to the Builder's bill (hay bales, ladders, a
spruce fence and a lantern; at levels 1 and 2 hay and the ladder are new kinds on it), as A's barrels do. Only B's
crate is what makes B expensive.

Questions for the owner [O]:

1. A, B, or A with B's vanilla touches?
2. Should the packed boxes follow the real stock (empty when he has none of a kind), or always look the same?
3. At levels 3 to 5 the dressing is in the attic only, and the ground floor's corner keeps its racks bare (six at
   level 5). Leave it so, or dress that corner too?

A note on S10e as shipped (not changed here): the attic's racks stand two high, and MineColonies draws a full rack's
two boxes 6 px up into the cell above, so while the lower rack holds anything its boxes show inside the rack above,
as in any MineColonies warehouse with stacked racks.

## 7. Picked (21 Nov 2026, ~01:55)

The owner let the build pick the recommendation overnight (his answer before he went to sleep: pick the recommended
variant and build it, he looks at the pictures in the morning). So, until he says otherwise:

1. **Variant A**, the tidy storeroom, without B's vanilla touches.
2. The packed boxes **always look the same** (they do not follow the real stock); simpler, and the racks already show
   the real stock.
3. At levels 3 to 5 the ground floor's corner **keeps its racks bare**; the dressing is in the attic.

These are [A] picks made for him, not [B] answers: if he picks otherwise in the morning, the change is a small follow-up.

## 8. Built in S10g1 (the pieces and the spots)

- **Blocks** (section 5): `storage_box` (`content` wreath, garland, lights, lanterns, gifts; `fill` full, low;
  `facing`) and `storage_coil` (`kind` garland, lights; `facing`), drawn by `tools/textures/storage.py` from this
  round's drafts (`model_box`, `model_coil` without B's loose end). Two changes to the drafts' geometry: the outer
  ring of lights in a full box of lights is 9 px across, not 11.4 (the draft's bulbs went through the box's walls),
  and the outer ring of a coil of lights is 11.8 px, not 13 (its bulbs ended 0.6 px outside the cell). Block only,
  `noLootTable()`, no collision, instant break, they stand on the block below like a carpet. Renders:
  `docs/renders/s10g/2026-11-21-s10g-blocks.png`.
- **Blueprints** (3.7): variant A's barrels in `tools/decorator_hut.storage_room` (`STOREROOM_BARRELS`). Levels 1 and
  5 are this round's plan A. Level 2: its third rack takes level 1's barrel cell at (3, 1, -3), and a barrel in front
  of that rack would wall it in, so the pair of standing barrels moves west along the back wall, next to the racks
  ((0, 1, -3) and (-1, 1, -3), the box of lights and the coil of garland on them); the hung set is level 1's.
  Levels 3 and 4: level 5's attic one barrel lower (single barrels where level 5 stacks two), with level 5's barrels
  at (-4, 6, -2) and (2, 6, -3) from level 3 on, so the six pieces and the fifteen hung blocks of level 5 are there
  too. Racks as shipped (2, 3, 6, 9, 12).
- **Spots** (3.5): the tags of `decorator_hut.STOREROOM`, one per cell. A piece's facing is not stored;
  `decorator_hut.spot_facing` is the rule the renders use and the placing code is to follow: away from the first
  whole wall block beside the spot (not a barrel or a rack), looking north, east, west and south in the building's
  own frame; a hung piece needs one, a standing piece with none faces south. It reproduces this round's facings at
  levels 1 and 5. `tools/build_pack.py` checks the rules of 3.1 and 3.5 on every level, with a self-test.
- **[V] of 3.5, proven** by the `csspots` probe: `IBuilding.getLocationsFromTag` gives the spots turned and mirrored
  with the building (a level 1 Workshop pasted R90 and a level 5 one pasted MIR_R270, checked against the pasted
  world: every packed spot is air on a barrel, every hung spot air beside a wall), and after an upgrade work order
  completes the cached tag map holds exactly the new level's tags (MineColonies' `AbstractWorkOrder.onCompleted`
  reads the new blueprint's hut data into the hut block; until then the old level's stay). The fallback is not needed.
  The code that reads them is `decor/StoreroomSpots.java`.
- Renders of all five levels with the pieces at their tags, packed and in the festival:
  `docs/renders/s10g/2026-11-21-s10g-levels.png`, and levels 1 and 5 on their own (`-l1-*`, `-l5-*`).

## 9. Built in S10g2 (the ledger kind, the check and the probe)

- **Ledger** (3.3): `DecorLedger.Kind.STOREROOM`, `Owner.storeroom(hut position)`, file version 2 with the same layout
  (a version 1 file or one with no version loads as before; the csstoreroom probe loads hand-written ones). The group's
  `festival` is the set a complete check left there (`""` packed), `level` the Workshop level, `tendedDay` the day of
  that check (`DecorLedger.setRoom`). `hasOutdoorEntries` and `countOutdoor` (buildings and the square only) are read by
  the AI's phase, `takingDown`, `upkeep`, the sweep's reset of "decorations up" and the hut page's count; `hasEntries`
  stays for `dropColony` and the sweep's `gone()`.
- **The check** (3.2): `decor/StoreroomWork.java`. Run from the AI's `decide()` ahead of the phase visits, whenever he
  is at his hut (inside the Workshop's box grown by 2, or within 6 blocks of the hut block), when the room is due (no
  complete check today for this target and level) and at once when the target turned; otherwise at most every 600
  ticks after a complete check, 100 after one that could not finish. A listed piece is kept when the target has the
  same block with the same properties at its cell, facing and lit aside, so a wall that changed beside a piece or a
  string of lights switched on causes no swap. An empty spot read (the hut's data not there, a Workshop with no tags)
  changes nothing. The facing is decorator_hut.spot_facing in the world: the four sides turned with the building's
  `getRotationMirror()`, our own pieces and listed cells counting as air, as they are in the blueprint.
- **The sweep** (3.3 point 4): a festive room goes after the window when **that Workshop** has no worker, not only
  when the colony has none. This covers the case of 3.3 and also an unstaffed second Workshop in a colony whose other
  Workshop is staffed, whose festive room would otherwise stay all year.
- **The switch** (3.3 point 6): `decorator.storeroomDressing`, in the Decorator's existing `decorator` section (this
  document's `decor.` prefix names no section of the config).
- **Upgrades** (3.4): proven by the probe with the new level built into the world as the Builder builds it; repairs
  open a work order the same way and are not run separately.
