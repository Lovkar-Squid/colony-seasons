# Colony Seasons 0.2.0: the Town Square building

Written 20 Nov 2026 for the owner's idea of the same day. Design round only: nothing is built, nothing is decided
until the owner answers section 5. It extends `docs/design.md`; every [B] rule there still binds unless this file
says, in so many words, that a rule changes and that the change needs the owner's yes.

Sources: three read-only research reports of 20 Nov 2026 (two-buildings, square-building, our-code; javap of
`minecolonies-1.1.1420-1.21.1.jar` and `structurize-1.0.839-1.21.1.jar`), our own source in
`src/me/lovkar/seasons/`, and a few javap spot checks made while writing this file (marked "checked"). Anything that
neither bytecode nor our source proves is marked **UNVERIFIED**.

Marks, as in `docs/design.md`:

- **[B] binding**: an owner decision or a safety rule. A builder does not change it; if it cannot be met, stop and report.
- **[A] advisory**: the intended shape; a builder may differ for a stated reason, written in the commit.
- **[V] verify first**: rests on something not proven from bytecode; the chunk that needs it proves it on the rig
  first and falls back as written.
- **[O] owner choice**: look and feel; section 5 lists them.

## 1. What the owner asked

The square stops being a single marker block and becomes a building of its own: a Town Square (or something like
it) that the player places, the Builder builds and upgrades, and the Decorator decorates by season and by festival,
or in the way the player orders him to in the square's window. The Decorator is employed at two places, the way the
Quarrier is employed at the Mine and works the quarries, and the Courier is employed at his hut and serves the
Warehouse: his job and home base stay his Workshop, and he is also assigned to the square. Later festivals (Spring
Bloom, Harvest Fair, Night of Lanterns) each get their own set on the same square. His addition of the same day
(`docs/IDEAS.md`): when Trade Post is installed, the square may later tie into it (market stalls), as an optional
runtime bridge only. Later on 20 Nov he added that with Trade Post installed the square should help the colony earn
money: section 7.

## 2. One worker, two buildings: how MineColonies does it and what we copy

### 2.1 The two MineColonies patterns (from bytecode)

**Quarrier, Mine and quarries.**
- The Mine (`BuildingMiner`) carries `BuildingModules.QUARRIER_WORK`, a `MinerBuildingModule` (a `WorkerBuildingModule`).
  That is his real employment: `getWorkBuilding()` is the Mine.
- Each quarry hut (`simplequarry`, `mediumquarry`, plain `DefaultBuildingInstance`, max level 1) carries a
  `QuarryModule extends AbstractAssignedCitizenModule implements IAssignsJob`, `getModuleMax() = 1`, NBT
  `"quarryassignment"` / `"miners"` (int[]) / `"isfinished"`. It has no worker module.
- `JobQuarrier.assignTo(IAssignsJob)` (checked): returns false if the module's `getJobEntry()` is not the quarrier's;
  returns true for a `QuarryModule` **without calling super**; otherwise calls `AbstractJobStructure.assignTo`. The
  super call is what sets `workBuilding` and removes the citizen from his old building, so the quarry never moves him.
- `JobQuarrier.findQuarry()` scans all colony buildings whose id contains "quarry" for one whose module
  `hasAssignedCitizen(citizen)`. `EntityAIQuarrier` uses it and shows "noquarry" when there is none.
- `QuarryModule.onColonyTick` auto-assigns quarriers with no quarry and drops assigned citizens whose job is no longer
  `JobQuarrier`.

**Courier, Courier hut and Warehouse.**
- Employment: `BuildingDeliveryman` with `COURIER_WORK` (`DeliverymanAssignmentModule`, a `WorkerBuildingModule`).
- The Warehouse carries `WAREHOUSE_COURIERS`, a `CourierAssignmentModule extends AbstractAssignedCitizenModule
  implements IAssignsJob, IBuildingEventsModule, ITickingModule, IPersistentModule`. NBT `"warehouse"` / `"couriers"`
  (int[]) plus `buildingHiringMode`. Max = warehouse level x 2.
- The link lives only on the served building. `JobDeliveryman.findWareHouse()` loops the warehouses for
  `hasAssignedCitizen`. `onColonyTick` auto-hires when `BuildingUtils.canAutoHire(building, mode, job)` allows it,
  and prunes citizens who lost the job (on the next colony tick, not at once).
- `AbstractAssignedCitizenModule.onDestroyed` unassigns everyone when the warehouse goes.

**Hiring window and message.**
- `CourierAssignmentModuleView` / `MinerAssignmentModuleView` open `SpecialAssignmentModuleWindow` with their own
  layout xml (name list, "manage" opens `WindowHireWorker`, "recall").
- `HireFireMessage(building, hire, citizenId, moduleRuntimeId)` (checked): looks the module up with
  `IBuilding.getModule(int)`, requires `instanceof IAssignsJob`, calls `setPaused(false)` and then `assignCitizen` or
  `removeCitizen`. It does **no job check of its own**; the client filters by the view's `canAssign`.
- `WindowHireWorker.doneClicked` moves a citizen out of his old hut only when the selected view is a
  `WorkerBuildingModuleView`; a secondary assignment view adds him without moving him.

### 2.2 The pattern we copy [A]

The Courier/Warehouse shape with the Quarry details:

| MineColonies | Colony Seasons |
|---|---|
| Mine / Courier hut (employment) | Decorator's Workshop, `BuildingDecorator`, unchanged |
| Quarry / Warehouse (served building) | Town Square, new `BuildingTownSquare` |
| `QuarryModule` / `CourierAssignmentModule` | new `SquareAssignmentModule` on the square |
| `JobQuarrier.assignTo` override | `JobDecorator.assignTo` override |
| `JobQuarrier.findQuarry()` | `JobDecorator.findSquare()` |
| `MinerAssignmentModuleView` + `SpecialAssignmentModuleWindow` | `SquareAssignmentModuleView`, same window, our layout xml |

Differences from MineColonies, each forced by the bytecode:

- [B] `SquareAssignmentModule` cannot extend `AbstractAssignedCitizenModule`: its `onAssignment` and `onRemoval` are
  package-private abstract (checked: `abstract void onAssignment(ICitizenData)`), so a class in our package cannot
  implement them. It extends `AbstractBuildingModule` and implements `IAssignsJob`, `IBuildingEventsModule`,
  `ITickingModule`, `IPersistentModule` itself, keeping an int[] of citizen ids like the courier module.
- [B] It must not be a `WorkerBuildingModule`: that module's `onRemoval` calls `job.onRemoval()` and cancels requests,
  which would strip the Decorator's job when he is taken off the square. Its view must not be a
  `WorkerBuildingModuleView` (that triggers the "move from old hut" branch).
- [B] `assignCitizen` refuses anyone whose job is not `JobDecorator`, because `HireFireMessage` does not check.
- [B] `JobDecorator.assignTo(IAssignsJob)`: return false if the job entry differs; return true for a
  `SquareAssignmentModule` without calling super; otherwise call super. `JobDecorator extends AbstractJob`, whose
  `assignTo(IAssignsJob)` is public (checked), so the override is legal.
- [B] Pruning is ours: `CitizenManager.removeCivilian` (death, dismissal) cleans only modules that are
  `AbstractAssignedCitizenModule`. Our module drops ids on every `onColonyTick` and on load when the citizen is gone
  or no longer a Decorator.
- [A] There is no cached list for custom building types (`RegisteredStructureManager` special-cases only town hall,
  warehouse and mystical site), so `findSquare()` loops `getServerBuildingManager().getBuildings().values()`. A
  colony has few buildings; cache per colony tick if the probe shows a cost.

Rejected: the BuildingExtension route (the farmer's fields). It needs no hut, but `SurvivalHandler` hard-codes the
plantation field and ships its own message stack, it has no levels, blueprint, tags or window, and a destroyed owner
leaves a dangling `buildingId` until the next load. It cannot give the owner's "bigger, richer square by level".

## 3. Options for the square

| | (a) Hut-type building | (b) Decoration with a controller | (c) Keep the festival marker |
|---|---|---|---|
| Placed by | build tool, hut item, Builder | build tool, `WorkOrderDecoration`, Builder | the player by hand |
| Levels | yes, 1 to `getMaxBuildingLevel()`, via `requestUpgrade` | path digits only, `WindowDecorationController` | none |
| Position tags | yes, `getLocationsFromTag` on `IBuilding` | yes, on the block entity, no `IBuilding` API | fixed box only |
| Window, settings | yes, `SettingsModule` + our info module | no (Build/Repair/Cancel and a name) | none |
| Decorator assignment | yes, section 2 | no module possible | no |
| MineColonies knows it | yes (`addNewBuilding`, `BuildingRemovedModEvent`) | no list, no mod-bus event | no |
| Leisure site | by our polling (as now) | native, if tagged `leisure` | by our polling (as now) |
| Built and rig-proven | Workshop pattern | Colonies at War walls | yes, 0.1.0 |

**Recommendation: (a), a hut-type Town Square, with the marker kept as the fallback.** It is the only option that
gives everything the owner asked for with MineColonies' own machinery: levels built by the Builder, tags that say
where the tree, stalls and lamp posts go, a window with the order, and a real two-building assignment. (b) cannot
host a module or a worker and would need our own registry; (c) has none of it. The marker stays for colonies that
never build a square and for every 0.1.0 world.

## 4. Design of the Town Square

### 4.1 The building

- [A] Building `colony_seasons:townsquare`, hut block `blockhuttownsquare` (an `AbstractBlockHut`, added to the valid
  blocks of our `colonybuilding` block-entity type, `SeasonsTileEntity`), item and crafting recipe like any hut,
  levels 1 to 5, **no worker module**. Building class: our own `BuildingTownSquare extends AbstractBuilding` (as
  `BuildingDecorator`), view `EmptyView`-like plus module views.
- [A] Modules: `SQUARE_DECORATOR` (`SquareAssignmentModule`), `SQUARE_SETTINGS` (`SettingsModule` with the order,
  section 4.4), `SQUARE_INFO` (info module and view). Producer keys are global (`BuildingEntry.getProducer(String)`):
  all ours start with `colony_seasons_`.
- [A] Blueprints `square/townsquare1..5.blueprint` in our own pack "Colony Seasons" (next to `decorator/workshop*`),
  drawn with `tools/voxel.py` by a new `tools/square_hut.py`. The pack is standalone (`StructurePackMeta` has no
  parent), so the square has our look in every colony style; several looks would be several stems ([O] 1).
- [A] No research gate (no effect id `colony_seasons:effects/blockhuttownsquare` exists, so it is ungated), as for
  the Workshop: a young colony can join in.
- [A] `getClaimRadius` override: 1 chunk at every level (the default grows to 2 at levels 4 and 5). [V]
- [A] The hut block is the anchor and stands on the square as a visible piece ([O] 3), reachable from the paving.
- [B] The square's own footprint (corners) is the same at every level, and the tags `tree` and every slot keep the
  same relative cell at every level. Then an upgrade never moves the ledger group key, and the first build is the
  only one that clears ground. Levels add paving, furniture and slots inside the same box.
- [A] The square is not decorated as a "building": `DecorWork.buildingsInOrder` skips `BuildingTownSquare` (it has no
  doors, and `DoorFinder` would scan a large box for nothing).

Blueprint tags [A] (names final in chunk Q1, all read with `getLocationsFromTag`):

| Tag | Count | Use |
|---|---|---|
| `tree` | 1 | centrepiece anchor: the cell the 0.1.0 marker stood in. Air above it is reserved for the grand tree and its 9x9 base |
| `gift` | 1 | gift pile (winter), the festival's table later |
| `launch` | 1 | firework crate and launch point (replaces `site.relative(SOUTH, 2)`) |
| `gather` | 8 to 24 | standing spots for the gathering (replace the computed ring) |
| `sit` | 2 to 12 | benches; also used by MineColonies' leisure wander |
| `lamp` | 0 to 8 | lamp-post tops: garland, lantern string or paper lanterns per set |
| `bed` | 0 to 8 | planter / flower-bed cells for seasonal and spring sets |
| `stall` | 0 to 4 | market stall corners, Harvest Fair and the later Trade Post bridge |

### 4.2 What each square level gives [A]

The tree size today comes from the Workshop level (`PieceLoader.pathFor(hutLevel)`). With a square, which level sizes
the centrepiece is [O] 5; the table assumes the recommended "lower of the two".

| Square level | The square itself | Slots | Winter set (with Workshop at the same level or higher) |
|---|---|---|---|
| 1 | paved centre with a ring of path, the hut block piece, 2 benches | `tree`, `gift`, 8 `gather`, 2 `sit` | small village spruce, gift pile |
| 2 | wider paving, 4 lamp posts | + 4 `lamp`, 12 `gather` | + garland on the lamp posts |
| 3 | 4 planters, low edge wall | + 4 `bed`, `launch`, 16 `gather`, 6 `sit` | full village spruce, firework crate, lantern strings on the posts |
| 4 | 2 stall pads, 8 lamp posts | + 2 `stall`, 8 `lamp` | the rich spruce, lights on every post |
| 5 | fountain or bandstand corner, 4 stall pads, full paving | + 4 `stall`, 8 `bed`, 24 `gather`, 12 `sit` | the grand tiered tree with the large star |

Building decorations (wreaths, garlands on houses) stay driven by the Workshop level, as in 0.1.0.

### 4.3 The Decorator and the square

- [A] **Assigning.** The square's window has a "Decorator" page (`SquareAssignmentModuleView`, through
  `SpecialAssignmentModuleWindow`): it lists the assigned Decorator, "manage" opens `WindowHireWorker` filtered by
  `canAssign` (job is Decorator and not already on another square), hiring mode through `BuildingHiringModeMessage`.
  `getModuleMax() = 1`: one Decorator works a square.
- [A] **Auto-hire** (default hiring mode "auto"): on `onColonyTick`, a square without a Decorator takes a colony
  Decorator who has no square, if `BuildingUtils.canAutoHire` allows it. A player who builds a square and has a
  Decorator gets a decorated square without opening a window.
- [A] **More than one square.** MineColonies does not stop a second one (`AbstractBlockHut.canPlaceAt` is a
  constant). 0.2.0 works **one main square per colony**: the built square (level 1 or more) with the highest level,
  ties to the lowest building id. Other squares stand as plain buildings; their window says "Only the main square is
  decorated in this version", and the managers are told once when the second one is placed. A Decorator serves at
  most one square. Several worked squares (one Decorator each, events at the main one) is a later step; the ledger
  key `Owner.square(pos)` already allows it.
- [B] The square is worked only by the Decorator listed in its module (`job.findSquare()` checked in
  `EntityAIWorkDecorator`). The marker fallback stays open to any staffed Decorator, as in 0.1.0.
- [A] His day keeps the 0.1.0 order: town hall, then the square, then buildings by distance; the square counts as one
  building of his `2 + 2 x level` a day.

Per phase (design 4 "His year"):

| Phase | At the square |
|---|---|
| Preparing | also assembles the kits of the set the square will need next (section 4.4), up to the cap |
| Decorating | places the active set into the tag slots, centrepiece first, in slices, by the [B] rules of 3.2 and 3.3 |
| Tending | once a game day: re-places what went missing, restocks the gift pile, swaps the set when the order or the season changed (old set down first, then the new one) |
| Hosting | stands at `tree`, the crowd uses `gather` spots, gifts and lighting as in 0.1.0 |
| Launching | from the `launch` slot |
| Taking down | the set goes when its window ends, by the exact-state rule, items to the Workshop |
| Off season | the seasonal set if the order wants one ([O] 4), otherwise the square stays bare |

Per festival (sets; only Winter ships with the square, every other set comes with its festival's version [B] owner
decision 6):

| Set | When (follow mode) | Centrepiece at `tree` | Slots |
|---|---|---|---|
| Winter Festival | 1 Dec to 6 Jan (existing window) | village spruce / grand tree with star | gift pile at `gift`, crate at `launch`, garland and strings on `lamp` |
| Night of Lanterns (later) | its window | a great lantern frame | paper lanterns on `lamp`, lit; floating-lantern particles at the gathering |
| Spring Bloom (later) | its window | a blossom arch | flower beds in `bed`, bunting between `lamp` posts |
| Harvest Fair (later) | its window | a harvest stack (hay, pumpkins, crates) | stalls at `stall`; optional Trade Post bridge |
| Seasonal touches ([O] 4) | in-game season, no festival | none | planters in `bed`: spring flowers, summer leaves, autumn pumpkins, winter evergreen boughs |

[A] Every centrepiece has a `lit` state, so "light the tree" generalises to "light the centrepiece" for later sets.
The lighting ritual (player right-click, or the Decorator after two game days) is unchanged for winter.

### 4.4 The order (theme) the player gives

- [A] One cycling button in the square's settings: `StringSetting(List<String>, int)` (checked, both constructors
  exist), values are lang keys (`getDisplayText()` runs them through `Component.translatableEscape`):
  1. **Follow the calendar** (default): the active festival's set during its real-date window, otherwise the seasonal
     touches of the in-game season (or bare, per [O] 4).
  2. **Always <set>**, one entry per set that exists in this version (0.2.0: Winter Festival; seasonal touches if
     chosen). The square keeps that set all year.
  3. **Bare**: the Decorator places no set on the square; events and the festival's fixtures still happen there.
- [B] Events follow the real calendar, never the order: gathering, gifts and fireworks happen on their dates at the
  square whatever its set. The festival's fixtures (gift pile at `gift`, crate at `launch`) are placed for an active
  festival under every order, Bare included, so the daily gift never depends on the theme [A]. Bare means "no
  set", not "no festival".
- [A] A stored value this version does not know (a later version's set, then a downgrade) reads as "Follow the
  calendar", with one log line. [V] how `StringSetting` reads an unknown stored value.
- [A] The window (info page) shows: the order; the set now on the square and its state (up, going up, coming down,
  waiting for materials, no room); the next change ("Winter Festival set from 1 Dec"); the assigned Decorator or "No
  Decorator: assign one on the Decorator page"; what the next square level adds. The Workshop's info page gets one
  line: "Square: level N, <set>, <state>" or "Square: festival marker" or "No square".

### 4.5 The gathering

- [A] `Gathering.site` order: the main square's `tree` cell, else the registered marker, else the town hall, else the
  colony centre (0.1.0 order with the square first). Standing spots come from `gather` tags instead of the computed
  ring (`spots`, `KEEP_RADIUS` 7, at most 24).
- [A] Leisure: `Gathering.leisureSite` keeps re-adding the site every poll, now with the square's hut position.
  `RegisteredStructureManager.cleanUpBuildings` removes any leisure site whose block entity is not a
  `TileEntityDecorationController`, so a lasting `addLeisureSite` on our hut is impossible; design 5's sentence and
  `research/b2-colony-apis.md` 2(b) are corrected by this. Our hut block entity is an `IBlueprintDataProviderBE`, so
  `EntityAICitizenWander.wanderAtLeisureSite` strolls inside the square's corners and uses its `sit` tags.
- [B] Who comes and when is unchanged (design 5, owner decision 7). The mixin pull stays: MineColonies' own leisure
  pick is about a quarter of 5 % of idle decisions, too weak alone.

### 4.6 Ledger and safety

- [B] Design 3.2 and 3.3 hold unchanged: place only into air, loaded chunks, inside this colony's claim; every block
  written to the ledger before the next; removal only on exact state; `decor remove` and `decor list` as now.
- **Rule change that needs the owner's yes** (not a look question, asked beside section 5): design 3.2 [B] "Nothing
  with collision is ever placed automatically, except at a `festival_marker` the player put down" becomes "... except
  at a festival marker the player put down **or inside the footprint of a Town Square the player built**". The
  spirit is the same (the player chose the place). Until he says yes, the builder keeps the old rule and the
  square's set is limited to blocks without collision, which rules out the tree.
- [A] Group key: `Owner.square(pos)` with `Kind.SQUARE` stays; `pos` means "the centrepiece cell": the marker's
  position, or the world position of the `tree` tag. `Gathering.treeRadius`, `Fireworks`, `TreeLighting`, `Gifts`
  and `DecorCommands` then keep working.
- [A] Resolver `decor/Squares.of(colony)`: (anchor, `RotationMirror`, kind MARKER or BUILDING, building id). Stored in
  `ColonyRecord` as `squareAnchor`, `squareKind`, `squareBuilding`; `DecorLedger.VERSION` 1 to 2, keys only added.
  Every reader of `ledger.marker(id)` switches to it: `DecorWork.squareTarget`, `Target.square`, `targetOf`;
  `DecorEvents.markerPlaced`, `markerRemoved`, `ownerGoneOrBusy`, `gone`; `Gathering.site`, `treeRadius`,
  `leisureSite`, `markerGone`; `TreeLighting.tree`, `light`; `Fireworks.site` (drop its `instanceof
  FestivalMarkerBlock` test).
- [B] A built square wins over the marker from level 1 on. While the square has an open work order it is "busy": its
  group comes down and nothing is placed there, and the marker is **not** used meanwhile (no tree jumping back and
  forth during an upgrade).
- [B] Any work order on the square (upgrade, repair, removal) takes the square's group down **server side at once**,
  as a removed building does now, in `SQUARE_SLICE` slices across ticks, before the Builder reaches the
  CLEAR_NON_SOLIDS stage (bytecode: `lambda$structureStep$6` sets blueprint-air cells to air on upgrade and repair).
  Items go to the Workshop (`sinkOf`); nothing is lost, and the set goes up again after the work order ends.
- [A] Extra safety: the set volumes in the square blueprints are `structurize:blocksubstitution` cells, which
  CLEAR_NON_SOLIDS skips (not an `AirBlock`) and the placement handler passes over. [V] first build, upgrade, repair.
- [A] `sweep.ownerGoneOrBusy` (SQUARE) also answers "busy" when the square building has a work order, and
  "gone" when the group's position differs from the resolver's anchor. `DecorEvents.Pending` gets a separate building
  position, because a square owner's position is the anchor, not the building.
- [A] `PieceLoader.Key` and `read` switch from `Direction` to `RotationMirror`; the marker passes
  `rotation(facing)` as now, the building passes `getRotationMirror()`. A mirrored square: if pieces survive the
  mirror on the rig, accept it; otherwise strip the mirror and rotate only. [V]
- [A] Paths: `pathFor(set, level)`; winter keeps today's files. A changed path already takes the old piece down first.
- [A] The 10 % rule and "no room" stay. On a square they should never fire (the volume is reserved); if they do, the
  text names the square ("Something stands in the Town Square where the tree goes"), new lang key.

### 4.7 Removal and upgrade

- **Upgrade** ([A]): the player asks for it in the hut window; the work order makes the square busy; the group comes
  down at once (4.6); the Builder builds; on the first colony tick after the work order closes, the Decorator puts
  the set back on his next visit, sized by the new level. Footprint and tag cells do not move ([B] 4.1).
- **Removal** ([A]): breaking the hut block (BREAK_HUTS permission) calls `AbstractBuilding.destroy()`, then our
  module's `onDestroyed`: unassign the Decorator, queue the square's group for server-side removal (also through
  `BuildingRemovedModEvent` in `DecorEvents.onBuildingRemoved`, which now queues `Owner.square(anchor)` too). The
  plaza blocks stay in the world, as for every MineColonies building; the module data (the order) is lost, the
  ledger is not. The resolver falls back to the marker if there is one.
- **REMOVE work order**: `skipRemoval` ignores air and substitution cells, so our blocks would stay; the server-side
  take-down at the start of the work order removes them first.
- **Mod removed**: as in 0.1.0, the README tells admins to run `decor remove` first; the square building itself
  becomes an "invalid building" in MineColonies when our pack is gone (`com.minecolonies.coremod.invalidbuilding`).

### 4.8 Migration from the 0.1.0 festival marker

- [B] No automatic conversion. A 0.1.0 world loads the v1 ledger as before; the resolver returns the marker; nothing
  changes until a Town Square reaches level 1.
- [A] When the square is built (level 1, not busy), the marker group's position differs from the resolver's anchor,
  so `ownerGoneOrBusy` takes the marker tree down by the exact-state rule, in slices, items to the Workshop. The
  Decorator then builds the set on the square from those items. One message to the managers (new lang key): "The
  festival marker is not used while the Town Square stands."
- [A] The marker block and its registration stay; it is the fallback if the square is removed.
- [A] A marker placed after the square registers as fallback and gets the same message.
- [B] A colony never has two active centrepieces: the resolver returns exactly one anchor, and every group whose
  position is not that anchor is taken down.
- [A] Downgrade to 0.1.x: v2 only adds keys and owner kinds do not change, so a 0.1.x reader loads the file; the
  square's group has no registered marker and comes down as "owner gone". The square building becomes an unknown
  building to MineColonies (UNVERIFIED what MineColonies does with a building whose type is no longer registered).
- [A] The 0.1.0 owner item 7 (a second marker) is folded in: a second marker or a second square tells the managers.

### 4.9 Trade Post

[A] The `stall` tags carry the festival market stalls. The optional Trade Post bridge is designed in section 7 and
built as chunk Q10.

## 5. Owner choices [O] (look and feel)

Plus one rule question (not look and feel, but it needs him): **may the Decorator place blocks with collision (the
tree) inside a Town Square the player built, as he does at the marker today?** Recommended: yes (section 4.6).
The Trade Post choices T1 to T3 are in section 7.4.

| # | Choice | Options | Recommended |
|---|---|---|---|
| 1 | Look of the square | A village plaza (cobble and stone brick, spruce benches); B market square (timber posts, striped awnings on the stall pads); C formal plaza (polished stone, brick edging, a fountain at level 5) | A, one look; B's awnings come with Harvest Fair |
| 2 | Size | A 15x15 at all levels; B 21x21 at all levels; C 25x25 at all levels | B (fits the grand tree's 9x9 base with room for the crowd; a fixed size keeps upgrades safe) |
| 3 | The hut block on the square | A a notice board; B a well; C a lamp post with a sign; D a fountain base | A (reads as "the square's office", easy to click) |
| 4 | Between festivals | A bare square; B small seasonal touches in the planters; C a full set every in-game season | B (the square never looks empty; small sets cost little to swap every 12 game days) |
| 5 | What sizes the centrepiece | A the square's level; B the Workshop's level (as in 0.1.0); C the lower of the two | C (both buildings matter, as in his "employed at both") |
| 6 | What the order offers | A Follow the calendar and Bare only; B also "Always <set>" for every set the version has; C also a choice of variant per festival (for example small spruce or grand tree in winter) | B |
| 7 | Name | A Town Square; B Village Green; C Festival Square | A |

Pictures to render for him (game textures, daylight plus one night view, made with the `design/` tools, no game run):

1. Square look A, B and C at level 1 and level 5, bare (six pictures, one sheet).
2. The picked look at levels 1 to 5 with the winter set (one sheet; also answers 2 and 5).
3. Hut block options A to D standing on the paving (one sheet).
4. Seasonal touches: the same level-3 square in spring, summer, autumn and winter planters (one sheet, answers 4).
5. One night render of the level-5 winter square with the lights lit (the 0.1.0 owner list still lacks a night view).

## 6. Build chunks for 0.2.0

Built with /ekipa-agentov as 0.1.0 was (worktree per chunk, independent verify, `judgeModel: "opus"`). Costs on the
0.1.0 build's measured scale: a normal Sonnet chunk about 0.5 to 1 point, an Opus or rigor-high chunk 1.5 to 3.

| Chunk | Goal | Main files | Tests | Builder, rigor | Points |
|---|---|---|---|---|---|
| Q0 Look round | pictures of section 5, owner answers | `design/src/square_*`, `design/README.md` | renders checked by the lead | Opus, normal | 1 |
| Q1 Shell and assignment | the building exists, a Decorator is assigned to it and stays employed at the Workshop | `block/BlockHutTownSquare`, `colony/BuildingTownSquare`, `colony/SquareModules` (assignment module + view), `colony/TownSquareSetup`, `JobDecorator` (`assignTo`, `findSquare`), `SeasonsBlocks` (BE valid blocks), one-level placeholder blueprint with all tags, lang, recipe, loot | probe `cssquare`: place and build the hut, `getLocationsFromTag` for every tag in 4 rotations and mirrored; assign through `assignCitizen` and through a `HireFireMessage`; non-Decorator refused; `getWorkBuilding()` still the Workshop; auto-hire; prune after death and job change; restart keeps the link; all of section 8 | Opus, high | 3 |
| Q2 Resolver, ledger v2, lifecycle | one anchor per colony; work orders, removal and migration are safe | `decor/Squares`, `DecorLedger` (v2), `DecorEvents` (`Pending`, `ownerGoneOrBusy`, `gone`, `onBuildingRemoved`), `DecorWork`, `DecorCommands`, `FestivalMarkerBlock` (message) | v1 ledger loads; marker to square switch takes the old tree down exactly; square removed falls back; upgrade work order takes the group down before CLEAR_NON_SOLIDS; REMOVE order; second square; colony deleted; restart in mid-switch; downgrade read by a 0.1.0 jar | Opus, high | 3 |
| Q3 Tag-anchored pieces and sets | pieces land on tags with `RotationMirror`; sets and the order | `PieceLoader` (`RotationMirror` key, `pathFor(set, level)`), `decor/SquareSets` (set catalogue, slots per level), settings module with the `StringSetting`, `EntityAIWorkDecorator` (square visit, set swap) | extend `cspaste`/`csdecorgate`: the winter piece on the square equals the marker version cell for cell in 4 rotations (+ mirror or stripped); order changes swap sets; unknown stored value reads as Follow | Opus, normal | 2 |
| Q4 Events at the square | gathering, lighting, gifts, fireworks use the square | `Gathering`, `Fireworks`, `TreeLighting`, `Gifts` (slot), AI hosting and launching | extend `csfestival`: crowd on `gather` spots, the hut is the leisure site, lighting and show from the tag cells | Sonnet, normal | 1 |
| Q5 Windows | the square's three pages and the Workshop's line | `SquareModules` views, `client/TownSquareInfoWindow`, gui xml, `DecoratorInfoModule(View)`, lang | `check_packets.py`, `check_windows.py`, dedicated-server boot (no client class loaded) | Sonnet, normal | 1 |
| Q6 Blueprints | the picked square in 5 levels, seasonal planters if [O] 4 is B | `tools/square_hut.py`, `tools/build_pack.py`, `blueprints/.../square/*`, `pieces/` for seasonal touches | build script "ALL OK" (ids, `floatcheck`, `walkcheck`, hut block reachable, same footprint and tag cells at every level), `cspaste`, renders | Opus, normal | 2 |
| Q10 Trade Post bridge (optional) | with Trade Post installed, festival goods have prices and the square's stalls pay the colony on festival days within a daily cap; without it nothing changes (section 7) | `compat/TradePostBridge` (ModList check, self-test, `credit` with clamp, coin value by reflection, config switch `tradePostBridge`), `data/mctradepost/item_values.json`, `decor/MarketStalls` (stock, sale, daily cap), `ColonyRecord` (`marketDay`, `marketPaid`), `SquareSets` (market set only while the bridge is on), stall pieces, the market line in Q5's info page, lang, config | unit tests: cap, clamp, paid-day record across a reload, no `com.deathfrog` reference in our built classes; rig probe `cstradepost` run twice: **without** the Trade Post jar (mod boots, no Trade Post class loaded, no `current_balance` written, no market set, the square cycle equals Q7's baseline) and **with** `mctradepost-1.06.012-1.21.1.jar` from the shared `voyager-libs` folder (our values load and `marketplaceValue` is above 0 for each priced item, the gift has none; one sale raises `current_balance` by exactly its price and removes one good; the cap holds over a colony day; a restart in mid-day pays nothing twice; the switch off stops payment; the jar removed after a payout: world loads, stat kept); dedicated-server boot both ways | Sonnet, high (Opus verify) | 2 |
| Q7 Full cycle and hostile review | find what the chunk tests missed | `tools/csfullcycle` modes `square-building`, `migrate-marker`, `builder-over-tree`, `trade-post` | the 0.1.0 full cycle on a square, with restarts; a reviewer told to break it (dupes through set swaps, ledger ghosts on upgrade, two squares, square in another colony's claim, money from nothing at the stalls) | Opus, high | 2.5 |
| Q8 In-game test | proof in the real client, pictures | lead | the S10 routine with a square | lead, high | 1.5 |
| Q9 Docs | design, README, changelog | `docs/design.md`, `README.md`, `docs/CHANGELOG.md` | text matches what Q7 proved | Sonnet, normal | 0.5 |

**Total: about 19.5 points (range 14.5 to 26)**, of which Q10 is 2 (1.5 to 3). Order: Q0 and Q1 first (Q1 runs on a
placeholder blueprint), then Q2, then Q3 and Q4; Q5 and Q6 beside them once Q1 is in; Q10 once Q3 and Q5 are in (it
needs the sets and the info page); Q7, Q8, Q9 last. Not included: the next festival's own set, blocks and events (its
own chunks), festival visitors (section 7.2 c), several worked squares.

Gate [A]: read the meter before and after Q1. If Q1 costs more than 4 points, stop and give the owner new figures.

Riskiest parts:
1. Q2, the lifecycle: a wrong rule removes a tree that should stand or leaves one that should go; the Builder racing
   our take-down on an upgrade.
2. Q1, a custom `IAssignsJob` module outside `AbstractAssignedCitizenModule`: MineColonies paths that assume that
   class (death cleanup, load, `getAllAssignedCitizen` counts) may treat the Decorator oddly.
3. Q3, tags and rotation in our own hut block entity end to end, and mirrored squares.
4. Q6, a large flat blueprint: terrain under it, the first build clearing whatever the player had there.

## 7. Trade Post (optional)

The owner's addition of 20 Nov 2026: when the player has Trade Post for MineColonies installed, the square ties into
it and helps the colony earn money. Source: a read-only code reading of the same day (javap of Trade Post 1.06.012
for 1.21.1, its lang and data files, and the Trade Post bridges in the owner's Voyager and Colonist Errands). Trade
Post keeps a colony balance in Tudarii (‡), shown on the Town Hall as "‡ Impact"; one Trade Coin is
`MCTPConfig.tradeCoinValue` points (1000 by default). Its Marketplace's shopkeeper sells goods from display frames,
its Resort pays the colony for citizens' vacations, and it fires no sale or income event.

### 7.1 Rules

- [B] **Optional only, a runtime bridge.** `ModList.get().isLoaded("mctradepost")` first. Money goes through
  MineColonies' own colony statistic `"current_balance"`
  (`colony.getStatisticsManager().incrementBy("current_balance", n, colony.getDay())`), which needs no Trade Post
  class. Reflection (`Class.forName` only after the ModList check) only to read `tradeCoinValue` (fallback 1000).
  No compile dependency, no import of `com.deathfrog.*`, no mixin, no write into Trade Post's fields. Voyager's photo
  booth and Colonist Errands do it this way today.
- [B] **Without Trade Post the square works exactly the same** as sections 4 to 6 say: the same sets, events, gifts and
  windows, no market set, no money line, no log line above debug. The price file of 7.2 (a) lies unread.
- [B] Every bridge call sits in try/catch, logs once and turns the bridge off until the next start. A server config
  switch `tradePostBridge` (default on) turns it off by hand. At start the log names the Trade Post version; any
  version other than 1.06.012 runs but is logged as "not tested". A start self-test reads
  `getStatTotal("current_balance")`; if that throws, the bridge stays off.
- [B] Payments run on the server thread only, then `colony.markDirty()` (MineColonies' statistics manager does not
  mark the colony dirty itself), so the Town Hall figure refreshes.
- [B] We never keep money in our own data, never mint or hand out Trade Coin items, never call Trade Post's
  `mintCoins` or `depositCoins`, never write its per-building economy statistics. If Trade Post is removed, the
  statistic stays in the colony data as a plain key (MineColonies keeps unknown names) and returns with the mod.

### 7.2 The pieces, best first

**(a) Prices for the festival goods, one data file [A].**
- What: our jar ships `data/mctradepost/item_values.json` in Trade Post's own format,
  `{"replace": false, "values": {"colony_seasons:<item>": n, ...}}`. Trade Post's loaders read the copy in every pack
  and merge them, so the Marketplace's shopkeeper can sell our goods like any priced item.
- Which goods: the decorations the Decorator crafts from requested materials (wreath, garland, lantern string, fairy
  lights, tree star, ornament and tinsel leaves) and each later festival's goods when that festival ships. Not the
  `gift` item, not the gift pile, firework crate or festival marker (7.3).
- Price [V]: at most the summed Trade Post value of the ingredients plus about 25 %, read on the rig from Trade Post's
  own generated values. The numbers are ours; no Trade Post price list is copied (Trade Post is GPLv3, our own data
  needs no licence change).
- Needs: no code. Without Trade Post nothing reads the file.
- Can break: a change of Trade Post's loader or file format (then our goods simply have no price). The merge order
  on a clashing key is UNVERIFIED, so the file lists only `colony_seasons:` keys, and [B] it never sets
  `"replace": true` (that would wipe the player's whole price list).

**(b) Festival market stalls on the square [A].**
- What: on the days of an active festival, with the bridge on, the Decorator sets up a market set on the square's
  `stall` tag cells (2 stalls at square level 4, 4 at level 5; below level 4 there is no market). He stocks each stall
  with festival goods from the Workshop. While the festival's gathering stands, colonists at the stalls buy: each sale
  takes one good out of a stall's stock and pays the colony that good's price from (a) through
  `incrementBy("current_balance", price, colony.getDay())`, the call Trade Post's own Resort and Voyager's photo
  booth make. No Marketplace is needed: the money is the stall's.
- Daily cap per colony ([O] T1), counted per colony day in our `ColonyRecord` (`marketDay`, `marketPaid`; keys only
  added, the ledger stays v2). Once the cap is reached the stalls stand but sell nothing that day.
- The stall set ([O] T2) is pieces under the same [B] ledger rules as every set (air only, exact-state removal, taken
  down when the festival window ends or a work order makes the square busy). Unsold stock goes back to the Workshop
  as goods.
- Window: the square's info page gets one line, only while the bridge is on: "Market: 2,400 ‡ today of 4,000 ‡",
  "Market: no goods in stock" or "Market: closed until the next festival".
- Needs: Trade Post loaded and the self-test passed; a square of level 4 or more; the collision rule change of 4.6
  (stall counters have collision; without the owner's yes there is no market); an assigned Decorator; goods in stock.
- Can break: Trade Post renames or moves `current_balance` (income silently stops; a once-a-day read-back after a
  payment notices the stat did not move and turns the bridge off with one log line); a festival's real-date window
  against colony days (we pay per colony day inside the real-date window); an unloaded chunk or an absent Decorator
  (the stalls stand, nothing is sold, nothing paid).

**(c) Festival visitors who also shop at the Marketplace [A], later.**
- What: on festival days a few guests arrive as MineColonies visitors. Trade Post's Marketplace gives a shopping task
  to every visitor of the colony that has an entity, so the guests would also buy at an open Marketplace (shopkeeper
  working, display frames stocked, chance scaled by its level), with no code of ours on the Trade Post side.
- Needs: visitor spawning of our own, which Colony Seasons does not have: a place to sleep (visitors without one
  despawn), removal after the festival, and keeping the tavern's recruit offer away from them [V]. That is a feature
  of its own, so (c) is not in 0.2.0; it suits the later festival versions (Harvest Fair).
- Can break: a later Trade Post that lets only tavern or station visitors shop (the guests then just do not shop);
  how a shopper's pick turns into the shopkeeper's sale is UNVERIFIED.

**(d) Not worth it.**
- A festival bonus on Marketplace sales (+X % on festival days): with no sale event we would poll the Marketplace's
  `cash.generated`, keep a baseline across restarts and building changes, and pay for sales that happen anyway, with
  no input of ours. Fragile and money from nothing.
- A time-limited `advertising` research effect on festival days: applying a research effect from outside for a few
  days is UNVERIFIED and would need a research datapack not tied to festivals.
- Trade Coins as festival gifts, or stalls that pay in coin items: coins can be deposited back, so this mints money as
  items and opens a dupe road through our own gift pile.
- Writing Trade Post's per-building economy or its display shelves: needs Trade Post classes and private fields,
  which is real patching.

### 7.3 Money from nothing and dupes

- **Prices (a).** Only goods made from requested, paid materials get a price, at most the ingredients' value plus a
  small markup, so crafting and selling is a trade, not a mint. The daily `gift` is free, so it never gets a price
  (otherwise every day's gift would be cash). The gift pile, crate and marker are the festival's own fixtures,
  returned exactly by every take-down; a price on them would turn set swaps into money.
- **Stalls (b).** Every payment consumes one good from stock (paid materials, gone after the sale) and pays at most
  its price from (a), so the stall and the Marketplace never disagree (no buying low at one and selling high at the
  other). Payment stops at the daily cap and happens only on festival days. "Paid today" lives in our saved record
  keyed by the colony day, so a reload, restart, chunk reload or set swap cannot pay twice. Stock taken down returns
  as goods, never as money. Sales are decided on the server; no client message can pay.
- **Visitors (c).** Trade Post's own sale path with goods the colony stocked; nothing of ours pays.
- **No coin items** are ever minted or handed out by us.
- **Overflow.** Trade Post keeps one 32-bit int per colony day; payments are a few coins at most and clamped before
  the call (as Voyager clamps the total), so no day's bucket can wrap.
- Trade Post already creates money itself (Resort vacations, coin drops from kills), so the aim is not a closed
  economy but a small, bounded stream tied to goods the colony made.

### 7.4 Owner choices [O] for Trade Post (balance and look)

| # | Choice | Options | Recommended |
|---|---|---|---|
| T1 | How much the festival market may earn a day | A about 1 Trade Coin a colony day (1,000 ‡) whatever the square; B about 1 coin per stall a day (2 at level 4, 4 at level 5); C about 2 coins per stall a day | B (a bigger square earns more, and a whole Winter Festival stays under about 150 coins) |
| T2 | Look of the stalls | A striped awnings in the festival's colours on timber posts, goods on the counter; B plain timber stalls with barrels and crates; C small hand carts under a lantern | A (reads as a market from afar; Harvest Fair reuses it in autumn colours) |
| T3 | What the stalls sell | A only festival goods the Decorator made; B also the colony's own produce from the warehouse (bread, flowers, cookies); C festival goods first, warehouse produce when they run out | A (ties the market to the festival and never eats the colony's food) |

## 8. UNVERIFIED points the first chunk (Q1) proves on the rig

Each with its fallback if it fails.

1. `SquareAssignmentModule` (plain `IAssignsJob`, not `AbstractAssignedCitizenModule`) works with `HireFireMessage`,
   `SpecialAssignmentModuleWindow` + our layout xml, `WindowHireWorker` (does not move him from the Workshop) and
   `BuildingHiringModeMessage`. Fallback: our own small assign message and window page.
2. `JobDecorator.assignTo` override keeps `getWorkBuilding()` on the Workshop through assign, unassign, restart and
   a Workshop upgrade; find any other MineColonies caller of `job.assignTo` with a non-worker module.
3. `AbstractBuilding.getAllAssignedCitizen` counts the Decorator at the square too: no side effect on the Workshop's
   "needs worker" state, the citizen's work-building lookup on load, or the town hall's job counts.
4. Pruning: a Decorator who dies, is fired or changes job leaves the square's list within one colony tick, and on
   load. Measure how often `onColonyTick` runs.
5. `getLocationsFromTag` on our hut block entity: every tag in four rotations and mirrored; after an upgrade; after a
   changed blueprint (`safeUpdateTEDataFromSchematic`); empty while the chunk is unloaded (consumers must wait).
6. `structurize:blocksubstitution` cells: cleared on the first build, skipped by CLEAR_NON_SOLIDS on upgrade and
   repair, ignored by REMOVE. Fallback: rely only on the [B] take-down before every work order.
7. The work-order "busy" signal arrives before the Builder's CLEAR_NON_SOLIDS stage reaches the square; measure the
   gap between the work order's creation and the Builder's first change in the box.
8. The `getClaimRadius` override takes effect and does not shrink an existing claim.
9. `StringSetting` with an unknown stored value on load (crash, reset to index 0, or keep).
10. Placement in survival through the build tool for a 21x21 hut with the anchor away from the centre; whether a
    per-colony limit is possible at all (`canPlaceAt` is constant; a check in `setPlacedBy` may be needed).
11. A dedicated server boots with the new views and windows (client classes only through the factory pattern).
12. Re-adding the square's hut position as a leisure site each poll is accepted, and colonists stroll inside its
    corners and use its `sit` tags.
13. What MineColonies does with a building whose type is no longer registered (downgrade, removed mod).

## Risks

1. Lifecycle ghosts in the ledger (anchor change, `Pending` position, busy detection): wrong removal or no removal.
2. The Builder clearing our blocks on upgrade or repair before the server-side take-down runs.
3. MineColonies code that assumes every `IAssignsJob` is an `AbstractAssignedCitizenModule`.
4. Tag rotation and mirror in our own hut block entity; the `tree` cell must never move between levels.
5. The collision-rule change needs the owner's yes; without it the square cannot carry the tree.
6. A large first build clears what the player had in the box (MineColonies' normal behaviour, but new to our mod).
7. `QuarryModule`, `CourierAssignmentModule` and `SpecialAssignmentModuleWindow` live in MineColonies `core`
   packages and can change between versions; keep the version range tight and add a boot check.
8. Weak native leisure pull: the gathering still needs the soft-fail mixin.
9. Trade Post's internal names (`current_balance`, `tradeCoinValue`, the price-file format) can change in any version;
   the bridge then stops paying, which the self-test and the read-back must catch (section 7.1).

## Owner answers (20 Nov 2026)

- [B] The Decorator may place blocks with collision (the tree, stalls) on a Town Square building the player built, as at a festival marker.
- [B] The tree size on the square follows the lower of the square level and the Workshop level.
- [B] Between festivals the square gets small seasonal touches (planters and the like); the full sets come only in festival windows.
- [B] The square window offers: follow the calendar, always one chosen set, or bare.
- Still open, with pictures: the look of the square, its size, the hut block on the square, the name, and the Trade Post questions T1 to T3.

## Addendum (20 Nov 2026): the marking wand

The owner also asked for street decorations. Proposed as part of 0.2.0: a Decorator's wand that marks spots around
the colony (snowman, lantern post, garland across a street, planter); see docs/IDEAS.md. It reuses the square's
ledger groups and the collision rule the owner approved for player-chosen places. Its own chunk (wand item, spot store
per colony, pieces for snowmen and lantern posts, probe) adds roughly 3 to 4 points; the look of the new pieces goes into
the 0.2.0 picture round.

## Q11 as built (the marking wand)

- The wand is an item; its mode (what goes on the next spot) is kept on the wand and cycled by a right-click in the air, so
  there is no window. A right-click on a block marks the cell in front of the clicked face; a garland takes two clicks on two
  marked posts; a shift-right-click removes a mark. Its holder sees the colony's marks as outlines (the mod has no particles).
- The marks are the colony's record in the decoration ledger (`marks`; the format stays 3, keys and kinds only added); every
  mark is a group of kind `STREET` at its first cell, and all marks of a colony share one slot of the day's budget. The
  thin street lights are one more group, `LIGHTS`, at the colony's centre.
- The pieces are free, as the 0.1.0 decorations that are no kit's; they have collision at the spot the player chose (the
  owner's answer for player-chosen places) and are not solid. A mark is refused inside any building's box, the Town
  Square's included, and within 5 blocks of the festival marker (the tree).
- The planter stands only in the festival windows, as the other street pieces; its block follows the in-game season (the
  Town Square's touches). The lantern posts and the street lights come on with the tree's lighting, or by hand.
- Tending, once a game day, serves the marks finished on an earlier day; a mark that is not done goes up only on a
  decorating visit, inside the day's budget. A tending visit notes the day also when it was turned away (the ground under
  a snowman gone, the Workshop's lights kind off), so the same group is not offered again on every call that day.
- The server switch is `decorator.streetLights` (the brief's `decor.streetLights`: the section is `decorator`), off by
  default.

## Owner answers, pictures round (20 Nov 2026) [B]

- Look A, village plaza; size 21 x 21 at every level; hut block A, the notice board; name Town Square.
- Street pieces for the marking wand: snowman A as the default and B as the second variant; lantern post B, the crook.
- Trade Post stalls: A, striped awnings; about 1 coin per stall per day; they sell festival goods only.
