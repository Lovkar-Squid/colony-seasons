# Building Colony Seasons

Plain `javac` and `jar` against the jars of the target modpack - no Gradle, no mappings step,
because NeoForge 1.21.1 mods run on official (Mojang) names. The version lives in
`resources/META-INF/neoforge.mods.toml` (`version="..."` on a line of its own, from column 0:
the build script reads it with a regex).

## What you need

- JDK 21
- NeoForge 21.1.x (the universal and the client jar), the Minecraft 1.21.1 client jar and its assets
  (`client-...-extra.jar`), and the libraries Minecraft links against (DataFixerUpper, fastutil, Guava,
  Gson, SLF4J, commons-lang3, JOML, the JetBrains annotations jar)
- MineColonies 1.1.1420, Structurize, BlockUI and Domum Ornamentum of the same pack
- `stubsrc/` holds two tiny stand-ins (`net.neoforged.api.distmarker.Dist` / `OnlyIn`) for the case that
  your NeoForge jars do not carry them; they are compiled into `stubs/` first

None of these jars are part of the repository (they are not ours to redistribute). On the author's PC they
sit in the shared test rig and the library folder named in `tools/rigpaths.sh`; that one file is the only
place where machine paths live, so building elsewhere means editing it.

## Build

```bash
bash tools/build.sh      # compiles src/, packs resources/, writes colony_seasons-<version>.jar at the repo root
bash tools/checks.sh     # lang keys, model textures, block files, and a sequential read of the jar; prints ALL OK
```

`tools/build.sh` calls the rig's `build-addon.ps1`, which is a plain `javac` plus `jar` run. The jar is
read back front to back (`tools/JarCheck.java`) because a launcher may have to read it that way.

## Headless tests (probes)

A probe is a tiny server-side mod in `tools/<probe>/` that runs on a dedicated NeoForge test server (fresh
flat world, MineColonies and its libraries loaded) and logs `[<probe>] CHECK <what>: OK|FAIL` lines and last
`[<probe>] RESULT <probe>: PASS|FAIL`, then halts the server. Every probe of this mod starts with `cs`.

```bash
bash tools/probe.sh csboot                # the mod loads, every register is wired, the config spec is registered
bash tools/probe.sh csconfig defaults     # every getter equals the hand-written defaults list; the client file it opens itself
                                         # is written with exactly the hand-written client keys (showClothes)
bash tools/probe.sh csconfig seeded       # a config file with a non-default value for every key is read back; the client file
                                         # of 0.1.0-beta.1 (showParticles still in it) loads, keeps showClothes, loses the old key
bash tools/probe.sh csclient              # the client config is registered, not loaded on a server, answers its defaults; the season packet clamps a bad id
```

```bash
bash tools/probe.sh cscalendar dates          # festival windows, seasons, saved data, the packet, the commands (fake clock)
bash tools/probe.sh cscalendar bridge-absent  # Serene Seasons not installed: own calendar, one log line
bash tools/probe.sh cscalendar bridge-present # a written-here stand-in jar (tools/sereneshim): the season follows it
bash tools/probe.sh cscalendar bridge-throws  # a stand-in that throws: falls back, one WARN line, no error-level line
bash tools/probe.sh cscalendar bridge-nonseasonal   # the stand-in's whitelist leaves the overworld out: falls back
bash tools/probe.sh cscalendar bridge-off     # calendar.useSereneSeasons = false: own calendar, no log line
bash tools/probe.sh csblocks                  # the decoration blocks: placed by hand in every facing, collision, drops,
                                              # popping off a removed wall, light when lit, block entity NBT
bash tools/restart.sh cscalendar              # two server runs on one kept world: saved state is read back
bash tools/probe.sh csdecorator               # the Decorator on a real colony: 3 game days, a night, rain, AI resets
bash tools/probe.sh csdecorator free          # decorator.needsMaterials = false: no request, nothing used, nothing made
bash tools/restart.sh csdecorator             # stock, cs_assembled, cs_decorations_up and the setting survive a restart
bash tools/probe.sh cspaste                   # every blueprint of the pack loaded and pasted through Structurize, the
                                              # world read back against tools/cspaste/res/cspaste/manifest.json
bash tools/probe.sh csdecorgate               # placing, the ledger and taking down: real huts of six style packs, edge cases,
                                              # the square with the probe's test piece, a real colony and the Decorator's AI,
                                              # the grand tree with grass on a planter cell (a lantern that cannot stand)
bash tools/probe.sh csdecorgate styles        # only the pasted huts (with their per-style and per-door lines) and the edge cases
bash tools/probe.sh csdecorgate square        # only the square (S10i: the Calendar page's packet says when the tree has no room)
bash tools/probe.sh csdecorgate lifecycle     # only the real colony and the AI
bash tools/restart.sh csdecorgate             # half a building, the ledger file on disk, a restart, nothing placed twice
bash tools/probe.sh csfestival                # the festival events on a real colony with the Decorator, a marker and the tree:
                                              # lit by a player and by the Decorator, gifts, the gathering (the mixin's
                                              # everyone day and the next), dusk fireworks (the Decorator stays up and
                                              # launches them), the midnight show, the season line, three game days of a
                                              # forced Winter Festival
bash tools/probe.sh csfestival nomixin        # the mod rebuilt without its mixin: it loads, the off-duty gathering works,
                                              # the dusk show still goes up (from the server)
bash tools/probe.sh csfestival voyager        # Voyager 0.4.0 next to it: both mixins apply, the Decorator launches the dusk
                                              # show, the Astronomer works at night
                                              # ...then the square of the third in-game test: the tree put up by his AI
                                              # while he and a colonist stand in its cells (nobody boxed in, he stands
                                              # outside, another building the same day), an armor stand in a gift cell
                                              # (left for the day after 3 empty visits, the tree ready and lit anyway)
bash tools/probe.sh csfestival newyear        # the New Year dusk of the in-game test replayed (sprinted from 11800), and the
                                              # Decorator asleep in the rain: the server launches the show from the crate,
                                              # from his stock and free
bash tools/restart.sh csfestival              # a gift taken and the tree lit, a restart: refused again, still lit, nothing repeated
bash tools/probe.sh csforester                # MineColonies' Forester with the festival tree and a natural oak in his work
                                              # area, three game days: he cuts the oak and never the festival tree
bash tools/probe.sh cseffects off             # the three effects switched off: nothing changes (happiness, crop growth, food over a game day)
bash tools/probe.sh cseffects on              # all three on: the happiness modifier and the client's total, half the growth steps inside a claim,
                                              # a quarter more food; also the Decorator's hut window page, his kind settings and the planner
bash tools/probe.sh csanswers                 # the owner's answers of 20 Nov 2026 (S10d) on a real colony with a worker and a knight:
                                              # the welcome line (the real login event on a fake player: once, word for word, two links,
                                              # the shared marker; not again, not when another mod set it, not with the switch off, kept
                                              # through restoreFrom); a doorless house and a pasted hut without its doors get one wreath on
                                              # an outside wall; what a player breaking free and paid decorations, the tree's gift pile and
                                              # crate, trunks and spruce leaves gets, a pop-off, the explosion's order, a real explosion of a free and a paid vanilla block, the take-down refund;
                                              # leaves at take-down (only distance changed: removed); a second marker (one notice); on the
                                              # everyone day the knight is never brought and the lumberjack comes (the mixin asked);
                                              # S10h: a door under a jettied upper floor (a hand-built house and the Medieval Dark Oak
                                              # level-3 town hall pasted with its doorways open, as in the fourth in-game test) gets the
                                              # wreath on the jetty's front with garland and lights; a marker pasted through Structurize's
                                              # placement call (no player) is no square and sends no notice
bash tools/restart.sh csanswers               # a player welcomed and saved through the server's player storage, read back after a
                                              # restart: the marker is set and a login sends no line
```

Hiring (S10b). `cshire` hires the Decorator the way MineColonies does it, not through the work module: every hire and fire
is MineColonies' own `HireFireMessage`, built from bytes and run through its server handler, with the colony's owner (a fake
player) standing where a player would stand, because the server drops a building message sent from more than 15 blocks away.
The Workshop is pasted with the command a player types (`structurize paste ... Colony.Seasons decorator.workshop1`). The rig's
colony never becomes active (MineColonies takes no fake player as a nearby player), so during the auto-hire steps the probe runs
MineColonies' building tick itself every 500 ticks, the interval of an active colony.

```bash
bash tools/probe.sh cshire     # the pasted hut's stored paths and corners; at level 1 and 5: auto-hire (automatic, and default with
                               # the town hall's setting), the window's fire and hire, a hire into a full Workshop, moving a lumberjack
                               # and a knight with the player near both huts and with the player at the Workshop only; an upgrade
                               # work order 1 to 2; the calendar page sent again after a new season and a new day, and not otherwise
bash tools/restart.sh cshire   # a knight moved from far away and a lumberjack left at work, read back after a restart
```

The whole cycle (S9). `csfullcycle` builds a real colony (a hand-built town hall and Decorator's hut at level 3, ten huts pasted
from the real blueprints of four style packs, four workers and two colonists with no job, a festival marker), stocks the hut with
exactly what its buildings and tree need, takes the first snapshot (a box round every building and the square, saved in the world
folder) and runs the fake clock from 28 Nov to 10 Jan, one or more game days a date, while the Decorator's own AI does all the work.
After every game day it checks four invariants: every ledger entry equals the world; every cell of the box that differs from the
snapshot is listed (nothing unlisted touched); every decoration item and vanilla item of the tree is still accounted for (hut,
citizens' packs, item entities, what the probe holds, paid entries); every entry lies in the colony's claim. The snapshot compares
block states and normalises only OPEN and POWERED on doors, gates and trapdoors, OCCUPIED on beds and LIT on blocks that are not ours.

```bash
bash tools/probe.sh csfullcycle                 # the whole cycle: decorations from 1 Dec, the tree lit, gifts, a broken wreath back
                                                # the next day, the gathering on 24 to 26 Dec, the dusk shows and the midnight show,
                                                # the take-down from 7 Jan, on 10 Jan the world equal to the snapshot cell for cell
bash tools/probe.sh csfullcycle nodecorator     # no Decorator's hut: nothing placed, the festive scarf, the gathering at the marker, at
                                                # the town hall once the marker is broken
bash tools/probe.sh csfullcycle nomarker        # no marker: buildings decorated and taken down, no square, no show, gathering at the town hall
bash tools/probe.sh csfullcycle emptywarehouse  # no materials: nothing placed, the requests stay open; an empty dusk notes nothing and
                                                # rockets that arrive during it give that dusk's show; the planner's time noted
bash tools/probe.sh csfullcycle effects         # the three effects on through the cycle, then switched off through the config spec
bash tools/probe.sh csfullcycle serene          # the Serene Seasons stand-in: the season follows it, the festivals the dates
bash tools/restart.sh csfullcycle               # three server runs on one world (phases.txt): to 12 Dec, to the first take-down visit
                                                # on 7 Jan, to 10 Jan; each reads the last one's state back
bash tools/probe.sh csfullcycle edges-low       # a 4-day year, every festival off, the gathering hours out of order
bash tools/probe.sh csfullcycle edges-high      # a 960-day year, gatherUntilTick 23999
bash tools/probe.sh csfullcycle hostile-items   # the hostile review: dupes through gift piles, gifts, take-downs, broken decorations, kits
bash tools/probe.sh csfullcycle hostile-free    # decorations free: what a player gets by breaking them every day
bash tools/probe.sh csfullcycle hostile-world   # the grand tree with short stock, sliced take-downs, unloading chunks, five years, a
                                                # deleted colony, touching claims, a second marker, the marker broken in the hours, the
                                                # hut destroyed while he hosts, a broken tree, decor remove mid-festival, a newer ledger file
```

The supply order and the storage room (S10e). `csstorage` builds a colony with a town hall, two houses, the Workshop pasted
from the shipped level-5 blueprint with the command a player types, a warehouse with four racks, a courier's hut and a
courier hired for the warehouse, and the Decorator; the courier really walks and carries.

```bash
bash tools/probe.sh csstorage    # S0 the pasted racks are the Workshop's containers; the supply rule table; Upgrade Storage through
                                 # the payload's server handler (refusals charge nothing, a far position is refused before any chunk is touched, three steps of one emerald block, racks
                                 # 27 > 36 > 45 > 54, a rack put in later comes in at the paid size) against MineColonies' own
                                 # Warehouse upgrade message as the control; finished wreaths from the warehouse by courier before
                                 # any ingredient request, part of the gap from the warehouse, a request the player would hold
                                 # cancelled within a pass (also while he does not prepare), ingredients as before with nothing anywhere; at the cap no request, his
                                 # storage loses exactly what he places; every output counted everywhere through decorating, a
                                 # courier refill and the take-down; (e2, S10h) the Storage tab's state and its module view, fed
                                 # the module's own bytes, through every click: the new step at once, the count the server left,
                                 # the wait ended by the server's step (a second click without reopening) or after 60 client
                                 # ticks for a refused click
bash tools/restart.sh csstorage  # two steps (the first in creative, free) and the stock at the cap, a restart: the step, the 12 racks
                                 # at 45 slots and the stock are back, no request, a third step works
```

The storeroom's pieces and spots (S10g1). `csspots` reads the spot tags the way the Decorator will (`decor/StoreroomSpots.java`
over `IBuilding.getLocationsFromTag`) against `tools/csspots/res/csspots/spots.json`, which `build_pack.py` writes.

```bash
bash tools/probe.sh csspots      # storage_box and storage_coil: no item, the empty loot table and no drops (broken or popped off),
                                 # no collision, an outline, nothing on pick-block, standing on a barrel and going with it, every
                                 # state's blockstate variant and models; the Workshop pasted at level 1 turned (R90) with the
                                 # command a player types: every spot tag turned with the building, nothing else of ours in the
                                 # cached tag map, the helper lists the same, packed spots air on a barrel and hung spots air beside
                                 # a wall in the world; upgrade work orders 1 to 5 finished as the Builder finishes them (the tags
                                 # are the old level's until the order completes, then exactly the new level's); a second Workshop
                                 # pasted at level 5 mirrored and turned (MIR_R270), its tags and world as above
```

The storeroom's dressing (S10g2). `csstoreroom` compares every room it sees, block state for block state and turned with
the building, with `tools/csstoreroom/res/csstoreroom/sets.json` (the packed and festival set of each level, written by
`build_pack.py` from `decorator_hut.storeroom_set`). It writes the rooms it placed at levels 1 and 5 to
`csstoreroom-dump.json` in the rig's server folder (never committed); `bash tools/render_s10g.sh --from-dump <that file>`
draws them and says whether they equal `storeroom_set`.

```bash
bash tools/probe.sh csstoreroom    # the ledger's files (version 1, no version, version 2 with a storeroom round trip); the Workshop
                                   # pasted at level 1 turned (R90): packed, dressed, packed by the check, a second check the same
                                   # day changes nothing (no block, no write, no budget, no status), free pieces drop nothing;
                                   # upgrade work orders 1 to 5 with the new level built as the Builder builds it (the sweep takes
                                   # the room off within its interval, nothing goes up while the order is open, then the new level's
                                   # set; one upgrade in a forced window); level 5 pasted MIR_R270; a Workshop with no tags (an empty
                                   # read changes nothing) and its removal; then the real Decorator: off season, a window's start and
                                   # end (festive until the outside is down, then packed; the outside ledger empty, the room still
                                   # listed), festival force and clear both ways, no calendar state yet (the room is left as it is),
                                   # a hire in the middle of a window, decor list and decor remove, no Decorator after a window (a
                                   # festive room goes, a packed one stays, also in an unstaffed second Workshop; with only packed
                                   # rooms listed the sweep says the decorations are down), the switch off and on, the Builder
                                   # clearing the cells
bash tools/restart.sh csstoreroom  # a check stopped half done in a forced window (5 off, 2 of 13 up) with nobody hired; after the
                                   # restart the ledger equals the world and the next check ends at the festival set
```

The Town Square (0.2.0, Q1). `cssquare` builds a colony with a town hall, two Workshops pasted with the command a player
types, a lumberjack's hut and two Decorators; every hire and fire is MineColonies' `HireFireMessage` and every hiring mode
its `BuildingHiringModeMessage`, built from bytes and run through the server handler with the owner standing near the
building, and the probe runs MineColonies' building colony tick itself, as `cshire` does. The square's tags are checked
against `tools/cssquare/res/cssquare/square_tags.json`, which `build_pack.py` writes.

```bash
bash tools/probe.sh cssquare     # the hut block, item, building and module registered, our block-entity type for both huts; eight
                                 # squares pasted (four turned, four mirrored): every tag through getLocationsFromTag turned with the
                                 # building, air in the world (a `sit` tag on its bench's stair), solid ground under `tree`, the same
                                 # after safeUpdateTEDataFromSchematic; two squares placed through MineColonies' survival placement
                                 # handler with the hut item (level 0: nobody assigned, no auto-hire; a second square is not stopped)
                                 # and built by a finished BUILD order (21 x 20 x 21, claim radius 0 then 1, the box claimed, nothing
                                 # unclaimed); the Decorator assigned by the module and by the window's hire and fire while he stays
                                 # employed at his Workshop (the same job, the Workshop lists him), refusals (a lumberjack, a jobless
                                 # citizen, a full square, a second square), the job's assignTo, a Workshop upgrade; hiring modes
                                 # MANUAL, AUTO, DEFAULT with the town hall's setting off and on, two squares and two Decorators;
                                 # pruning after a removal, a fire at the Workshop and a new job (gone from the list at once, the
                                 # id dropped at one colony tick or before the next assignment); the view read from the module's
                                 # packet; the square left out of the Decorator's buildings; a square broken; its tags with its
                                 # chunk unloaded (empty once the block entity goes; the rig keeps it, finding H2) and back after
bash tools/restart.sh cssquare   # a square with its Decorator on MANUAL and a square whose Decorator was removed without a colony
                                 # tick, read back before any colony tick (the first kept, the second pruned when the save was read)
```

The square's lifecycle (0.2.0, Q2). `cssquarelife` builds a colony with a town hall, a Builder's hut with a builder and the
Workshop pasted with the command a player types with its Decorator, both citizens as MineColonies data only (no AI of theirs
runs, so nobody walks, builds or decorates); the Town Squares are placed through MineColonies' survival placement handler and
built by what the Builder does when he finishes an order (the order's `onCompleted` with its blueprint, `onUpgradeComplete`,
the level, MineColonies' construction event). Every work order is MineColonies' own (`requestRepair`, `requestRemoval`, the
work manager). Decorations are seeded by the probe, each cell listed before its block is set (our trunk, vanilla spruce logs
and leaves); the date is fixed in July. `tools/cssquarelife/v010/DecorLedger.java` is the decoration ledger of the tag
`v0.1.0-beta.1` with its package line changed, the 0.1.0 reader of the downgrade check; `tools/check_reader.py` (a step of
`checks.sh`) compares it with the tag.

```bash
bash tools/probe.sh cssquarelife     # the ledger's files (no version, version 1, version 2 with a storeroom, version 3 with the square's
                                     # mark round trip, one mark key alone reads as none) and a version 3 file in the 0.1.0 reader (one
                                     # warning, every group, the marker and budget; its rule takes the square's group down; it saves 2);
                                     # the switch: a marker's tree with a cell the player changed and a foreign block, a square at level 0
                                     # (nothing changes), built (the mark at the end of the tick, one notice, the tree down in sweep slices
                                     # by the exact-state rule, items in the Workshop, a further pass writes nothing); the Decorator on the
                                     # square (the Workshop's worker state, his work building and the job counts unchanged); REPAIR,
                                     # REPAIR with cells the Builder cleared first, UPGRADE through the work manager (the watch) and REMOVE
                                     # (ticks from the order to an empty group, nothing lost, nothing put up after); the completed removal
                                     # (back to the marker); a square broken (its group gone the same tick); a second square (one notice,
                                     # the tie kept, an order on it changes nothing, a higher one moves the tree once); tags that cannot be
                                     # read (the stored anchor stands in; a higher square that cannot be located changes nothing); a
                                     # building type that is no longer registered; Structurize's placer with the CLEAR_NON_SOLIDS skip rule
                                     # over substitution cells; in a second colony a square built where its marker stood (the marker block
                                     # replaced as the Builder would: its group goes, no ghost entry, no notice), a marker put down after
                                     # the square (the fallback, one notice), and the colony deleted (its mark and marker off, a new colony
                                     # inherits neither)
bash tools/probe.sh cssquarelife builder   # section 8 point 7 with MineColonies' own Builder AI: the square's plaza set as built, the builder
                                     # given an entity, two REPAIR orders (the hut window with this Builder named, and one through the work
                                     # manager, which the watch sees); the ticks from each order to our group empty, to the Builder's first
                                     # stage, his CLEAR_NON_SOLIDS, his first change in the box (a torch on the paving) and the order done;
                                     # our group down before his first stage, no block of ours touched by him, every item back, nothing lost
                                     # (the rig's colony is never active, so the probe runs the work manager at MineColonies' 20 ticks)
bash tools/restart.sh cssquarelife   # a switch stopped half way between two of the rig's own sweeps: the ledger file on disk, the mark and
                                     # marker read back, no second notice, the Decorator's work building; the sweeps finish the tree and
                                     # the world equals the expectation cell for cell
```

Each finding that goes to the owner instead of a fix is a `NOTE FINDING H<n> owner:ask|fyi` line. `tools/restart.sh` runs the phases a
probe lists in its `phases.txt` instead of `restart-save,restart-load`.

The pack boot (a live check that never blocks a merge): `bash tools/packboot.sh` boots the owner's full modpack set headless, once without
the jar and once with it, and reports new ERROR lines naming the mod and crash reports. It reads the pack instance's mods folder under
`%USERPROFILE%` (only reads it, and fails if its listing changed), copies the test rig's server install into one work folder under the
system temp folder, picks a free port outside Windows' reserved ranges, sets aside jars that cannot load on a dedicated server (and lists
them), and exits 0 when clean, 1 on a new error or crash, 2 when it cannot run here.

`python -I tools/check_packets.py` (a step of `checks.sh`) reads the Decorator's info module and its view and compares, field
by field, what the server writes with what the client reads; `tools/check_windows.py` checks that every pane a window class
asks for is in its BlockUI layout.

`tools/probe.sh <probe> [mode]` builds the mod and the probe, then runs the rig. A probe folder may hold
`res/` (its own `neoforge.mods.toml`), `rigjars.txt` / `rigjars-<mode>.txt` (extra jars for every mode or one; `%REPO%` is this repo),
`modjar-<mode>.txt` (one jar run instead of the mod's own in that mode: csfestival nomixin), `prepare.sh` (builds them first), `rigconfig/` (a config file seeded into the
fresh world) and `rigconfig-<mode>/` (the same for one mode). The run fails on a probe `FAIL` and on any `ERROR`
or `Exception` line that names the mod or the probe. The rig runs one test server at a time, so a run may wait
for another one to finish. `tools/restart.sh <probe>` runs the probe's modes `restart-save` and `restart-load` on one kept world
(through `tools/rig/run-rig-keep.ps1`, a copy of the rig's runner that wipes the world once and holds the lock across both runs).
`tools/sereneshim/` is a few lines of our own that stand in for the Serene Seasons API in the bridge modes; its jar stays in `tools/out/`.

If the server dies with `FAILED TO BIND TO PORT`, Windows has reserved the rig's port
(`netsh interface ipv4 show excludedportrange protocol=tcp`). `bash tools/private-rig.sh` makes a copy of the
rig on another port next to the repository; `tools/rigpaths.sh` then uses it automatically.

## Winter clothes check (no game)

```bash
bash tools/clothescheck.sh   # ClothesRules + HatModels + Season compiled against Gson only: who wears what, MineColonies' December hat,
                             # the shipped hat allow-list; then tools/check_clothes.py (also a step of checks.sh)
```

`tools/check_clothes.py` reads the citizen model ids out of the MineColonies jar (`ModModelTypes.class`) and checks every id
in `hat_models.json` against them, every texture the clothes code names and its size, and that nothing is shipped into
MineColonies' `nether` style folder.

## Generated files

Chunks that generate resources (blueprints, textures, sounds) document their scripts here.

- `python -I tools/gen_clothes.py` paints the clothes textures, `resources/assets/colony_seasons/textures/entity/scarf_*.png` and
  `wool_hat_*.png` (64x32, the layout of `ScarfModel` / `WoolHatModel`). Pure Python, the same bytes on every run.
- Preview renders of the clothes on MineColonies' models (`docs/renders/s6/`), with Voyager's entity preview pipeline copied here:
  `bash tools/preview.sh` runs both steps: `tools/mc_models.py` plays every citizen model's `createMesh()` bytecode (`javap -c`, JDK from
  `$JDK_BIN`) into `tools/out/mc_models.json`, a copy of their boxes that stays out of the repository; then
  `tools/entity_preview.py` builds the sheets (our boxes are read out of the two model classes)
  and runs Blender headless on `tools/mcrender.py` (the jar and Blender paths are in `tools/rigpaths.sh`).

### Decoration blocks (S2)

```bash
bash tools/textures/make_all.sh     # textures, block and item models, blockstates -> resources/assets/colony_seasons/
python -I tools/gen_blockdata.py    # loot tables, crafting recipes, block tags -> resources/data/
bash tools/render_decor.sh          # look-check renders (Blender 5.1) -> docs/renders/s2/
RENDER_OUT=docs/renders/s10d RENDER_TAG=s10d-after bash tools/render_decor.sh square-day square-night closeup-day closeup-night
```

`tools/textures/*.py` draw every texture as character grids over small palettes, started from the looks the owner
picked (`design/src/make_textures.py` stays the record of them); nothing is hand-edited, so change a drawing there and
run the script again. Nothing is read from the game's textures: the ornament and tinsel leaves (S10d) name the game's own
`minecraft:block/spruce_leaves` in their models with `tintindex` 0, the client's colour handlers (`client/LeafColors.java`)
give it vanilla's evergreen colour, and our textures hold only the ornaments, lights and tinsel drawn on top
(`tools/check_models.py` checks the models and that no pixel of ours is a spruce-leaf pixel). The renderer tints the two
blocks like spruce leaves and lifts an element that repeats an earlier element's box a little, so the overlay does not
fight the leaves. A light that shines at night has its own model element with NeoForge's
`neoforge_data` (`block_light` and `sky_light` 15) in the `lit=true` model. `tools/check_blocks.py` (in `checks.sh`)
checks that every block has its blockstate, models, item model, lang line, loot table and recipe, and that lit models
glow and unlit ones do not. One named exception: the festival tree's trunk (`festival_trunk`, S10c) has no item of its
own, so no item model and no recipe, and its loot table drops a spruce log (`gen_blockdata.py` writes it, `tree.py` its
blockstate on vanilla's spruce log models). The storeroom pieces (`storage_box`, `storage_coil`, S10g1, drawn by
`tools/textures/storage.py` from the S10g design round's drafts) are block only: `check_blocks.py` NO_DROP wants no item
model, no item registered, no recipe, no loot table file and `noLootTable()` in their registration, and no other block
may have `noLootTable()`. `tools/render_decor.py` uses the design's renderer (`design/tools`): `mcmodel.py` reads the
mod's assets and, for a night scene, lights it with block and sky light and vanilla's lightmap; Blender is found at
its default path or through `BLENDER`.

### The Decorator (S3)

- `python tools/gen_decorator_hut.py [--render <png>]`: the Decorator's hut block and item models (vanilla textures only);
  `--render` draws it with the design renderer in `design/tools` (Blender, see `design/README.md`).
- `python tools/gen_decorator_skin.py`: the Decorator's 8 citizen textures (faces cut from MineColonies' Florist skins in
  `$MINECOLONIES_JAR` or the MineColonies jar in `$SEASONS_LIBS`) and his icons in all eight style folders;
  `python tools/preview_skin.py --sheet <png>` makes a flat front/back sheet of all eight.
- `tools/check_kits.py` and `tools/check_skins.py` (run by `tools/checks.sh`) check the shipped kits and those textures;
  `check_kits.py` also fails when an item comes back into itself through the kits and our crafting recipes (tags expanded).

### Blueprints (S5)

```bash
python -I tools/build_pack.py "<mods folder>"          # writes the pack and the paste probe's manifest, then checks; ALL OK
python -I tools/build_pack.py --check "<mods folder>"  # the same checks, and that the files on disk are what it writes
bash tools/render_s5.sh                                # renders for the look check (docs/renders/s5/) and the pack icon
```

The pack is `resources/blueprints/colony_seasons/colony_seasons/` ("Colony Seasons", its own pack: it works with every
colony style). `<mods folder>` holds the Structurize and MineColonies jars (`tools/checks.sh` passes the rig's
`basemods/`); the client assets jar comes from `$SEASONS_LIBS`. Everything is written by scripts, the same bytes on every
run (`tools/nbt.py` is a small NBT writer, so the scripts need no packages and run with `python -I`):

- `tools/decorator_hut.py`: the Decorator's hut, look "Workshop with shopfront", `decorator/workshop1..5.blueprint`.
  Level 5 is the picked hut A of `design/src/huts.py`; levels 1 to 4 grow towards it on the same plan. Not to be
  confused with `tools/gen_decorator_hut.py` (S3), which writes the hut block's model. `storage_room(level)` (S10e) is
  the one source of the storage room: the ground floor's back-east corner at levels 1 and 2, the attic from level 3,
  racks and barrels only (2, 3, 6, 9, 12 racks in the hut); `build_pack.py` checks it (racks and vanilla blocks, inside
  the walls on a floor, off the ladder's way up, a rack stays a rack upwards, more racks every level, the shipped counts).
  S10g1 dresses it (variant A of `docs/storage-look-design.md`): its barrels (`STOREROOM_BARRELS`) and the storeroom's
  spot tags in every level (`STOREROOM`: `cs_pack_winter_<piece>` for a box or coil on a barrel, `cs_hang_winter_<piece>`
  for a festival piece on a wall face; `spot_facing` is the facing rule). `build_pack.py` checks them on every level
  (in air inside the walls on the room's floor, off the ladder, nothing above a rack or behind a window, packed pieces on
  a barrel and never hung, hung pieces beside a whole wall block, both sets, the walk with either set placed; the
  barrels into the blueprint's air and none above a rack or behind a window, `workshop(level, barrels=False)`) after a
  self-test that plants each fault once, and writes `tools/csspots/res/csspots/spots.json` for the `csspots` probe.
- `tools/square_hut.py` (0.2.0, Q1): the Town Square, look A "Village plaza", `square/townsquare1.blueprint`: one
  placeholder level 21 x 21 (box y 0..19, so the grand tree fits) with every tag of design 4.1 (`tree` at the centre,
  `gift`, `launch`, 8 `gather`, 2 `sit` on the benches' seats, 4 `lamp` over fence posts, 4 `bed` over a planter, 2
  `stall`) and the notice board as its hut block at (2, 1, 8); Q6 draws the five real levels on the same box and cells.
  `build_pack.py` checks it (`check_square` after a self-test: the anchor and its name tag only, the box, the tag counts,
  tags on air except `sit`, standing room, solid ground under `tree`, the 11 x 11 column over it empty, the hut block
  reachable from the south entrance) and writes `tools/cssquare/res/cssquare/square_tags.json`.
  `python -I tools/gen_square_hut.py` writes the hut block's blockstate, block and item models and its notes texture (our
  own pixels over vanilla wood, a PNG writer of its own); `--check` is a step of `checks.sh`.
- `tools/tree_pieces.py`: the tree pieces the Decorator places at the festival marker, `pieces/village_spruce_small`,
  `village_spruce_full`, `village_spruce_rich` (tree A) and `grand_tree` (tree B) of `design/src/trees.py`. A piece is
  drawn facing north; its anchor is the marker's cell and stays air; tags `gift_pile`, `firework_crate`, `star`
  (positions relative to the anchor, read with Structurize's `BlueprintTagUtils.getBlueprintTags`) and `invisible`
  (the build tool does not list the pieces). Trunk cells are `colony_seasons:festival_trunk` (spruce log textures, not a
  log for the Forester: our own block in no log tag, S10c; it costs and drops a spruce log); decorations are unlit.
- `tools/build_pack.py` (copied from Voyager's and adapted, with `tools/voxel.py`, `tools/floatcheck.py` and
  `tools/walkcheck.py`): every block id against the client jar, our blockstates and the mod jars with real properties;
  decorations unlit; no floating blocks; nothing that would drop (ladders and wall decorations need a whole block
  behind, lanterns a block that holds them); no bed; the hut block reachable from the door and every floor cell
  walkable (ladders climbed); one box and one anchor for the five levels; level 5 and the full and grand trees against
  the picked designs (only the cells listed in `DESIGN_CHANGES` may differ); the piece contract; a read-back of every
  file. A step of `tools/checks.sh`.
- `tools/cspaste/` (from Voyager's paste test and `worldcheck.py`): the paste probe. Its manifest is written by
  `build_pack.py`; the world is read back in the game, so the probe needs no region-file reader.
- `tools/render_s5.py`: the shipped blueprints read back and drawn with the design renderer (`design/tools`) on the
  design's ground and camera; MineColonies' jar (`$MINECOLONIES_JAR`) gives the racks' model. The S10e cutaways of the
  storage room are rendered only when named: `bash tools/render_s5.sh attic-l3 attic-l5 corner-l1` writes
  `docs/renders/s10e/<date>-s10e-*.png` (front wall and roof left out) and leaves the S5 renders and the pack icon alone.
- `bash tools/render_s10g.sh [blocks l1 .. l5 levels]` (`tools/render_s10g.py`, S10g1): the contact sheet of the
  storeroom pieces on a barrel next to their textures, and the storeroom cutaways of the shipped blueprints with the
  pieces drawn at their tags, packed and in the festival, to `docs/renders/s10g/` (levels 1 and 5 on their own, all five
  levels on one sheet).
### Placing (S4)

- `python -I tools/csdecorgate/make_piece.py` writes the `csdecorgate` probe's own square piece (a small asymmetric test tree
  under the four piece names, in the probe's pack "Colony Seasons Test" in `tools/csdecorgate/res/blueprints/`) with a
  hand-written NBT writer, the same bytes on every run; `--check` (a step of `checks.sh`) says whether the committed files
  are in step. The probe lists the same cells once more and checks the placed tree against them in all four facings.
- `tools/check_decor.py` (in `checks.sh`): every block the Decorator places tells the ledger from `onRemove` and passes its
  drops through `DecorDrops.filter` (S10d), only `DecorPlacer` sets blocks among the Decorator's code, and the festival marker
  registers itself.
- `tools/check_links.py` (in `checks.sh`, S10d): both links in the mods.toml description with a thank-you, the Discord /
  Support line of the Workshop window, the welcome line word for word as the owner approved it, and the README.

### Festival events (S7)

- `python tools/sfx/chime.py` and `python tools/sfx/song.py` synthesize the festival's two sounds (the chime of the lit tree,
  the short waltz of the gathering, both our own) into `resources/assets/colony_seasons/sounds/festival/` and a preview of
  each into `docs/renders/s7/`; `tools/sfx/synth.py` holds the shared partials, envelopes and room. They need numpy, scipy
  and ffmpeg on the PATH, and write the same bytes on every run.
- `python -I tools/gen_gifts.py` writes the gift loot tables `data/colony_seasons/loot_table/gifts/{winter_daily,winter_festival,new_year}.json`
  (`--check` says whether the committed files are in step). Pack makers replace them with a datapack file at the same path.
- `tools/textures/gift.py` (run by `make_all.py`) draws the wrapped gift item; `python tools/render_gifts.py <out.png>` (Pillow,
  `SEASONS_LIBS` for Minecraft's icons and names: source `tools/rigpaths.sh` first) draws the review sheet of the gift and its
  three tables, `docs/renders/s7/2026-11-19-s7-gifts.png`.
- The csfestival players are friends of the colony: MineColonies' colony protection answers a right-click first (the tree's
  listener runs at LOW), so a visitor neither lights the tree nor takes a gift. Bedtime in the probe means MineColonies' sleep
  routine (`SLEEP`, `FIND_BED`, ...): the test colony has no beds. The step names avoid the words the rig greps for.
- `tools/check_festival.py` (in `checks.sh`): the mixin fails soft (a non-required config listed in the toml, `require = 0` on
  every injection), every sound has its file and subtitle, the gift tables are in step and name real items, the gathering ends
  before bedtime, and only `TreeLighting` sets blocks in the festival package.
- `tools/csfestival/prepare.sh` makes the extra jars of the probe's modes: for `nomixin` the mod built again with `jar` from the
  same `build/` and `resources/` without the mixin (`tools/out/csfestival-nomixin/`, run instead of the real jar, which stays as
  it is), for `voyager` a copy of the Voyager 0.4.0 jar (its path is `VOYAGER_JAR` in `tools/rigpaths.sh`) in `tools/out/`.
