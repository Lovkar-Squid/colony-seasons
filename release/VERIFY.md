# What the final pass must check

The page texts (`README.md`, `release/curseforge-description.md`, `CHANGELOG-0.1.0-beta.1.md`, the mods.toml
description) were written from `docs/design.md` while S4, S5, S7 and S8 were still being built. After S9 and the
in-game test (S10), go through this list. For each line, find the proof (a probe result, the S9 report or the
in-game test), and change the sentence in all the files that carry it if the result differs. Delete a line from
the page rather than keep a sentence nobody proved.

State when this list was made: S0, S1, S2, S3 and S6 merged; S4, S5, S7 and S8 not yet.

State after S9 (20 Nov 2026, branch ekipa-s9): S0 to S9 and the S11 drafts built; S10 (the in-game test) still to
come. Under each item: "Proof:" names the probe, mode and check that proves it; "Changed:" gives the new sentence,
already applied to every page file that carries it; "S10:" says what the in-game test still has to look at. The
hostile review's ids (H1 to H19) are the attacks of `tools/csfullcycle/Hostile.java`.

State after S11b (21 Nov 2026, branch lane-s11b, from main 83e1c39: S0 to S10h, S10g1 and S10g2): every claim of the
README and the CurseForge page checked against the code. The CurseForge page claim by claim is the table below; what
S11b changed is listed under it; the items of S9 and S10 follow as the record. Code places are under
`src/me/lovkar/seasons/` unless a path says otherwise. "Look" marks a claim that only an in-game look can prove (the
probes run headless on the rig and see no picture).

## The CurseForge page, claim by claim (S11b)

`release/curseforge-description.md`: about 7,800 characters, no images (CurseForge needs absolute image links, and
the screenshots go into the project's gallery, see the end of this file).

| # | Claim on the page | Proof | Look |
| --- | --- | --- | --- |
| P1 | Seasons run in the game, festivals on real dates | `calendar/CalendarManager.java:96-113` (the season from the bridge or `SeasonCalendar.state`, the festivals from `DateSource.today()`); cscalendar dates | |
| P2 | In December he hangs wreaths, garlands and lights and builds a tree on the square | `decor/DecorPlanner.java:84-93` (amounts by level), `decor/PieceLoader.java:107-114` (the tree per level); csdecorgate styles, csfullcycle base | yes: a real colony decorated (item 1) |
| P3 | Striped scarves, the gathering on festival days, a small gift a day, New Year fireworks | `client/ClothesRules.java` (`outfit`), `festival/Gathering.java`, `festival/Gifts.java:141-158`, `festival/Fireworks.java:77-80`; csfullcycle base, csfestival | scarves: yes (csclient proves the layer, not the picture) |
| P4 | After 6 January he takes it all down, and again next year | `SeasonsConfig.java:44` (decorUntil 01-06), `decor/DecorWork.java:48-50` (the take-down); csfullcycle base (on 10 Jan the world equals the first snapshot) | |
| P5 | Requires NeoForge 1.21.1, MineColonies 1.1.1420 or newer in 1.1, Structurize and Domum Ornamentum; Serene Seasons optional; server and every client | `resources/META-INF/neoforge.mods.toml:15-51` (every dependency `side="BOTH"`, Serene Seasons not listed); csboot, `tools/packboot.sh` (item 25) | the client side: yes |
| P6 | A new job, the Decorator's Workshop, levels 1 to 5 | `colony/BuildingDecorator.java:38,130`, `colony/DecoratorModules.java:25-28`; csdecorator, cspaste | |
| P7 | Supply order: his own storage, then finished pieces from the warehouse, then materials | `decor/Supply.java:44-52` (`plan`), `ai/EntityAIWorkDecorator.java:311-377` (`prepare`); csstorage (a warehouse and a real courier), `restart.sh csstorage` | |
| P8 | Materials such as leaves, string, dye, paper, torches and glowstone dust | `resources/data/colony_seasons/decorator_kits/*.json` (glass panes and gold nuggets too, hence "such as") | |
| P9 | `decorator.needsMaterials = false` makes his work free | `SeasonsConfig.java:164-167`, `ai/EntityAIWorkDecorator.java:248-252`; csdecorator mode free, csfullcycle hostile-free | |
| P10 | Town hall first | `decor/DecorWork.java:181` (`nextToDecorate`: the town hall, then the square, then the nearest building); csfullcycle base | |
| P11 | Level 1: a wreath above the main door; higher levels garland beside it and lights above it; the grand tree with the large star at 5 | `decor/DecorPlanner.java:20-27,84-93`, `decor/PieceLoader.java:107-114`; csdecorgate styles and setup, cspaste | yes: a level 1 and a level 5 hut (item 1) |
| P12 | A building with no outside door gets one wreath on the wall nearest its hut block | `decor/DecorPlanner.java:160` (`planDoorless`), `decor/DoorFinder.java:33` (`MIN_OUTSIDE_SKY`); csanswers | |
| P13 | Only into empty air, inside his claim, every block written down; takes down exactly those, only if unchanged; your own builds left alone | `decor/DecorPlacer.java:44-60` (the cell rule and the removal rule), `decor/DecorLedger.java`; csfullcycle every mode (I1 to I4), csdecorgate lifecycle, hostile-items H5 | |
| P14 | The trunk is the mod's own block, so a Forester never cuts the tree | `block/FestivalTrunkBlock.java`, `decor/PieceLoader.java:42-44`; csforester, `tools/build_pack.py` (no log block in a piece) | |
| P15 | Racks in a storage room, 2 at level 1 and 12 at level 5 | `tools/decorator_hut.py:18-20,228,240-243` (2, 3, 6, 9 and 12); `tools/build_pack.py` (the room's rules), cspaste | yes: renders only so far (`docs/renders/s10e/`) |
| P16 | Upgrade Storage at level 5: one emerald block a step, three steps, every rack one row larger, like the Warehouse's | `colony/BuildingDecorator.java:60-66,228-251`, `network/UpgradeStoragePayload.java:88-130`; csstorage (c, e2), `restart.sh csstorage` | yes: the tab after a click (S10h fixed its redraw, which the fourth in-game test saw stale) |
| P17 | He dresses the storeroom: packed outside the festival; from the first day of the window until his take-down is done, nearly empty boxes and garland, a wreath and fairy lights | `decor/StoreroomWork.java:94-104` (`target`), `decor/StoreroomSpots.java:21-25` (the pieces); csstoreroom, csspots, `restart.sh csstoreroom` | yes: only renders of the rig's rooms so far (`docs/renders/s10g/*-s10g2-*.png`) |
| P18 | `decorator.storeroomDressing = false` leaves the room bare | `SeasonsConfig.java:177-181`, `decor/DecorEvents.java:318-327` (`roomGoes`: the switch off removes the room); csstoreroom | |
| P19 | Place a marker on the square, inside the colony, and the tree goes there | `block/FestivalMarkerBlock.java:67-81,94-103`, `decor/DecorEvents.java:147-163`; csdecorgate square | |
| P20 | Only a marker a player places by hand counts | `block/FestivalMarkerBlock.java:78` (`placer instanceof Player`); csanswers (a paste through Structurize's own placement call) | |
| P21 | When the tree is finished you are told; a right-click lights the ornaments, the star and the lights on the buildings; after two game days the Decorator lights it | `festival/TreeLighting.java:24-31,40,108,121,165`; csfestival, csfullcycle base | yes: the lights at night |
| P22 | From Workshop level 2 a gift pile; from 1 December one gift a day for each player of the colony | `decor/PieceLoader.java:60,289` (no pile below level 2), `festival/Gifts.java:141-158`; csfullcycle base, csdecorgate square, csfestival (a visitor's click is refused by MineColonies' protection) | |
| P23 | 24 to 26 December: colonists walk to the tree in the afternoon, the Decorator hands them gifts, a better gift | `festival/Gathering.java:40-60`, `festival/Gifts.java:50-52`, `SeasonsConfig.java:45-46,62-63`; csfullcycle base, csfestival | yes: the colonists walking (two probe runs saw slow paths, S9) |
| P24 | 31 December and 1 January: fireworks at dusk; a countdown and a big show at midnight if the server runs through it | `festival/Fireworks.java:52-80,404`, `SeasonsConfig.java:198-200`; csfullcycle base, csfestival newyear | yes: the rockets in the sky |
| P25 | The gifts come from loot tables | `festival/Gifts.java:50-52`, `resources/data/colony_seasons/loot_table/gifts/` | |
| P26 | A winter scarf in five colours, a wool hat without headgear of its own, the striped scarf 1 Dec to 6 Jan, client only, nothing saved | `client/ClothesRules.java` (`COLOURS`, `outfit`), `client/ClothesLayer.java`; `tools/clothescheck.sh`, csclient | yes |
| P27 | 48 game days, twelve a season, changeable; a new world starts in the season of the real date; follows Serene Seasons | `SeasonsConfig.java:31-34,132-140`, `calendar/SeasonCalendar.java:25-27`, `calendar/CalendarData.java:36-48`, `calendar/SereneBridge.java`; csconfig, cscalendar | the real Serene Seasons: yes (item 26) |
| P28 | Three effect switches, all off; with them off only looks, sounds, materials and gifts change | `SeasonsConfig.java:68-77,206-228`; cseffects off and on, csfullcycle effects | |
| P29 | The commands table; operators for all but `status` | `command/SeasonsCommands.java:29,48-60`, `command/DecorCommands.java:32-42`; cscalendar, csdecorgate lifecycle | |
| P30 | The config switches table, keys and defaults | `SeasonsConfig.java:54-81` (defaults), `:164-234` (keys); csconfig defaults | |
| P31 | The Workshop window's switches: Prepare all year, Decorate, wreaths, garlands, lights, the tree | `colony/DecoratorModules.java:34-43`, `colony/BuildingDecorator.java:48-58`; cseffects (the planner honours them) | |
| P32 | Only two festivals, the others later | `SeasonsConfig.java:40` (`FESTIVAL_IDS`) | |
| P33 | Without a Workshop: no tree, gifts or fireworks; the colonists dress and gather at the marker or the town hall | `festival/Gathering.java:157-165` (`site`); csfullcycle nodecorator and nomarker | |
| P34 | Streets are not decorated | no road code; design 3.4 | |
| P35 | The happiness bonus is not in the citizen window, only in the hut window | `effects/HappinessEffect.java:18-24`; cseffects on | |
| P36 | MineColonies' own crops are not slowed in winter | `effects/CropEffect.java:14,54` (`CropGrowEvent.Pre` only); cseffects on ("[V] MineColonies' own crop block grows, and does not fire CropGrowEvent.Pre") | |
| P37 | English only | `resources/assets/colony_seasons/lang/` holds only `en_us.json` | |
| P38 | Uninstalling: `decor remove` first; the trunk goes with the mod, the spruce leaves stay, at level 5 also the planter's stone bricks, podzol and lanterns | `tools/tree_pieces.py:141-155`; hostile-world H18 | |
| P39 | The GitHub link | must exist and be public before the upload (item 29): a private repository is a dead link for visitors | |
| P40 | The Discord and Patreon links; one chat line the first time a player joins a world, once in that world; `links.showWelcome = false` turns it off | `links/Welcome.java:29-39,66-77,90-99`, `SeasonsConfig.java:230-235`; `tools/check_links.py`, csanswers | |

Found by S11b in the code and not changed (only the page texts and one tooltip were in scope; for the owner):

- `SeasonsClientConfig.showParticles` is in the client config with a comment that promises snow over the tree, the
  glow of lit decorations and firework sparks, but nothing reads it and the mod has no particles of its own (the
  only ones are the hearts the server sends at the gathering, `festival/Gifts.java:208`). The pages now say it does
  nothing yet.
- The hut window never says that the tree does not fit at the marker: `DecorLedger.squareProblem` is set
  (`decor/DecorWork.java:645`) and csdecorgate checks that it is "noted for the hut window", but
  `colony/DecoratorInfoModule.java` never reads it, and the status line then reads "Decorated Festival square: 0
  placed, 0 still open." What the player gets is the managers' chat line once a game day; the pages now say that.

## What S11b changed in the pages

README:

- The picture is the S10d render of the square (the leaves as shipped), called a render.
- A new world starts in the season of the real date "(as in the northern hemisphere)" (`SeasonsConfig.java:137-139`).
- The Decorator: what his two skills do (`ai/EntityAIWorkDecorator.java:126-128,148-155`); the stock cap, 16 at level
  1 to 48 at level 5 (`colony/BuildingDecorator.java:42-46,135-137`); the supply order (S10e, P7); every kit
  ingredient named after "such as"; kits in `data/<namespace>/decorator_kits/` (`decor/Kits.java:59`); what the tree
  and the show ask the warehouse for (`ai/EntityAIWorkDecorator.java:690-703,903-905`); with "Prepare all year" off he
  waits for the window (`ai/EntityAIWorkDecorator.java:254`).
- His round: the town hall, then the square, then the building nearest his Workshop, and the day's number of
  buildings, 2 and 2 more per level (`decor/DecorWork.java:44-45,131-133`).
- Tending: "puts back what went missing and refills the gift pile" became "puts back what went missing, the gift
  pile too": the pile has no stock to refill in 0.1.0 (`block/GiftPileBlockEntity.java:18-22`, `festival/Gifts.java:47`).
- "On festival days he ... sets off the fireworks" became who launches which show: he the dusk show when he can be up,
  else the server; the midnight show always the server (`festival/Fireworks.java:61-66,372-401`).
- New subsection "The Workshop's storage" (S10e and S10g): the racks, Upgrade Storage with its range and permission
  (`network/UpgradeStoragePayload.java:39,103`), the storeroom, the "Decorate" rule and the switch.
- Where decorations go: the wreath's rows (just above, one higher, or beside: `decor/DecorPlanner.java:51,56`), the
  garland beside it and the lights above it, the front of a jetty (S10h, `decor/DecorPlanner.java:66`), and a house
  with inside doors only counts as doorless (S10d).
- With no Decorator at the window's end, the items of the sweep go to a Workshop if one stands, else they are lost
  (`decor/DecorWork.java:777-801`, item 5, H16).
- The hut window: the line of the day's buildings; the Discord / Support line is under the list, always in view
  (S10h), not its last line; "four more switches" became "five more" (`colony/DecoratorModules.java:37-42`); the
  Storage page.
- The marker: inside the colony's claim (`block/FestivalMarkerBlock.java:94-103`); without it no gift pile and no
  fireworks either (`festival/Fireworks.java:470`); breaking it takes the tree down (`decor/DecorEvents.java:166-177`);
  a second marker does not take over (H14); "the hut window says there is not enough room" became the managers' chat
  line once a game day (see above).
- Lighting: the strings of lights "on the buildings" (`festival/TreeLighting.java:26-28`).
- Gifts: from Workshop level 2 (`decor/PieceLoader.java:60`), `events.giftsPerPlayerPerDay`, the colony's
  protection, all three gift tables.
- Clothes: `showParticles` does nothing yet (see above).
- The food effect: "takes a quarter" became "the extra share ... (a quarter with the default factor)"
  (`effects/FoodEffect.java:82-97`).
- Crops: "grew one of them 4000 steps" became "made one of them try to grow 4000 times"
  (`tools/cseffects/CsEffects.java:924`); "probably do not take part" in What it does not do became "do not take
  part", as the effects section and P36 say.
- The config table: the defaults.
- Links: "on the last line of the Calendar page" became "under the list"; "once per player and never again" became
  "once per player in each world": the marker is player data, saved with the world (`links/Welcome.java:66-99`).
- Commands: `decor list [colony]`, and "in this dimension" for both decor commands (`command/DecorCommands.java:33-46`).
- No Decorator: "the off-duty colonists gather" became "the colonists still gather": `events.whoComes` applies there
  too (`festival/Gathering.java:280`).
- Uninstalling: the grand tree's planter stays too (`tools/tree_pieces.py:141-155`).
- Credits: the trunk uses the game's spruce-log models (`resources/assets/colony_seasons/blockstates/festival_trunk.json`),
  and the Decorator speaks with MineColonies' Florist voice lines (`colony/DecoratorSetup.java:55`).

CurseForge page: rewritten round the table above, with a requirement line, the commands table, the config switches,
a Workshop section (S10e, S10g), the doorless wreath (S10d), the hand-placed marker (S10h), the Forester (S10c), the
gift pile from level 2, the Discord and Patreon links with the chat line and its switch (S10d). "Lights beside it"
became "garland beside the wreath and lights above it"; the list of materials got "such as"; "probably ignore the
winter slowdown" became "are not slowed in winter"; the planter on uninstalling.

`CHANGELOG-0.1.0-beta.1.md`: the S10 work (the supply order, the storage room and Upgrade Storage, the storeroom, the
doorless wreath, the jetty, the hand-placed marker, the second marker's line, the Forester, free pieces that drop
nothing, the guards at their posts, the links line), the three blocks with no item, and the two limits above.

`en_us.json`: the Wreaths switch's tooltip also names the one wreath on the wall of a building with no door.

`release/curseforge-page-coming.md` is the text while no file is up and is replaced by the description at the
upload; S11b left it as it is.

## The S9 and S10 record

The items below are the list of S9 and S10, kept with their proofs; S11b added "Changed (S11b)" where a page
sentence moved.

## Rests on S4 (placing, ledger, take-down)

1. The Decorator places a wreath on the main door and, by level, garland and lights beside it. Amounts by level
   in the README table (design 3.4).
   Proof: csdecorgate (styles: real huts of six packs at levels 5 and 1, the amounts table in setup; lifecycle: 12 a
   building at level 3); csfullcycle (base): "2 Dec: every building with door slots is decorated" with the real AI at level 3.
   S10: look at a level 1 and a level 5 hut.
2. He places only into air, in loaded chunks, inside his own colony's claim.
   Proof: csfullcycle (every mode): the daily invariants I2 (no unlisted cell of the box changed) and I4 (every entry in the
   claim); hostile-world H13 (two colonies with touching claims, both decorated, no entry outside its own claim) and H10
   (a visit to a building whose chunk unloaded places nothing and loads nothing); csdecorgate (edges: the claim edge).
3. He writes every placed block to a list saved with the world and takes away only a block on that list that
   still has the recorded state; blocks built by players or cleared by the Builder are never touched.
   Proof: csfullcycle (every mode): I1 (every entry equals the world) after every game day and, on 10 Jan, the world equal
   to the first snapshot cell for cell; csdecorgate lifecycle (a swapped block, the Builder's CLEAR_NON_SOLIDS);
   hostile-items H5 (a paid wreath a player breaks is forgotten and left to him).
4. A building under construction or upgrade is skipped and its decorations come off first.
   Proof: csdecorgate lifecycle ("an open work order: the building's decorations are removed within 200 ticks", "skipped
   while the order is open").
5. With no Decorator when the window ends, the server takes the decorations down itself as chunks load.
   Proof: csdecorgate lifecycle (the hut destroyed in the window, then the sweep); hostile-world H16 (the hut destroyed while
   he hosts: the rest stays up in the window, the sweep clears all after it) and H10 (cells in unloaded chunks wait and come
   down once loaded).
6. A building with no free wall gets nothing; the building keeps its kind of lights.
   Proof: csdecorgate styles (huts without a door get nothing; the light kind and the lantern colour order come from the
   position); csfullcycle base (pasted huts with no door slot are marked done with nothing placed).
7. `/colonyseasons decor list` and `decor remove <colony>` exist and work as the README says (design 3.3).
   Proof: csdecorgate lifecycle (list, remove, permission 2) and restart-load (the lost items said); hostile-items H4
   (decor remove together with the take-down and the sweep: each paid item handed back once); hostile-world H18.
8. After the window he takes down what he put up and keeps the items in his hut.
   Proof: csfullcycle (base): "7 Jan / 8 Jan: the take-down goes on, the count falls", and on 10 Jan "every tracked item
   is back where it was (I3 exact)" and the world equal to the first snapshot; restart (csfullcycle): a take-down across a
   restart. The grand tree comes down in slices over a few visits (hostile-world H9, H9b, H9c).
9. The festival marker: the tree stands where it is placed, facing as the marker faces; with at most a tenth
   of the cells blocked (and no trunk cell) he builds the rest, otherwise nothing and a note in the hut window.
   Proof: csdecorgate square (the position formula in four facings, the 10 % rule, the trunk rule, the note); csfullcycle base.
   Changed (S11b): the note reaches the colony's managers as a chat line once a game day; the hut window does not show
   it (`DecorLedger.squareProblem` is set but never read), so README and the CurseForge page say the chat line. The
   marker counts only when a player places it by hand (S10h), inside the colony's claim.
10. Uninstalling: `decor remove` first; the tree's leaves stay (design 3.3; since S10c its trunk is the mod's own
   `festival_trunk` and goes with the mod).
   Proof: hostile-world H18: decor remove in the middle of the festival leaves nothing listed, the square as it was before
   the tree and every paid item back in the hut; without it, the tree's vanilla spruce leaves would stay (S9 counted 122
   vanilla logs and leaves on the full-size village spruce, before the trunk became our block).
   Changed (S10c): README and `release/curseforge-description.md`, "the decoration blocks disappear with the mod (the
   tree's trunk too), but the spruce leaves of the tree stay".
   Changed (S11b): "and at level 5 also the stone bricks, podzol and lanterns of the grand tree's planter"
   (`tools/tree_pieces.py:141-155`; those are vanilla blocks of the piece).

## Rests on S5 (hut and tree blueprints)

11. The Workshop can be built with the build tool, levels 1 to 5, and the tree sizes match the table: small
    village spruce at levels 1 and 2, full size at 3 and 4 (more ornaments at 4), the grand tiered tree with
    the large star at level 5. The gift pile at level 2 and the firework crate at level 3.
   Proof: cspaste (every blueprint of the pack pasted and read back); csdecorgate square (the gift pile skipped below level
   2, the crate below 3); csfullcycle base (level 3: the full-size spruce with its gift pile and crate).
   S10: build the Workshop with the build tool, levels 1 to 5.

## Rests on S7 (festival events)

12. Gifts: one small gift a day per player per real day from the first day of the window, a better gift on
    24 to 26 December, the gift of New Year days holds a few rockets. The gift opens into a roll of
    `colony_seasons:gifts/winter_daily` (and `gifts/winter_festival`) and a pack can replace the tables.
   Proof: csfullcycle base (2 Dec "one gift a player a real day (gifts/winter_daily), the second click refused", 3 Dec
   again, 24 Dec winter_festival, 31 Dec new_year, 7 Jan closed); restart-mid ("a second gift on 12 Dec after the restart
   is refused"); hostile-items H1 (two colonies' piles in one tick: one gift) and H2 (a pile a player put in a tree cell
   hands out nothing); csfestival.
   Changed (S11b): the gift pile stands only from Workshop level 2 (`decor/PieceLoader.java:60,289`), so README and the
   CurseForge page say "from Workshop level 2"; the tending "refills the gift pile" is gone (the pile has no stock in
   0.1.0, `block/GiftPileBlockEntity.java:18-22`).
13. Lighting the tree: the colony's players are told when it is finished; a right-click lights the ornaments,
    star and strings with a chime and a few rockets; the Decorator lights it after two game days.
   Proof: csfestival (a player's click, the Decorator after two game days); csfullcycle base ("the tree is lit within two
   game days of being finished", every listed light lit); hostile-world H17 (a broken tree refilled and lit again).
14. The gathering on 24 to 26 December in the afternoon of the game day (ticks 8500 to 10500 by default), a
    short tune plays, the Decorator hands out gifts.
   Proof: csfullcycle base (24, 25, 26 Dec: the off-duty colonists gather at the marker in the hours, the song plays, the
   Decorator hands colonists gifts; 23 and 27 Dec nobody is called); csfestival.
15. `events.whoComes`: default everyone once per real day, off-duty colonists on the other game days; the
    mixin fails quietly if MineColonies moves its method.
   Proof: csfestival (default, nomixin, voyager).
16. Fireworks at dusk on New Year from the Decorator's rockets; with no rockets in his stock the dusk waits for them
    (rockets that reach his stock before the dusk ends still go up that evening). A countdown and a big show at
    midnight if the server runs through it (`events.midnightShow`).
   Changed: "On New Year, watch the fireworks from the square at dusk. If the Decorator has no rockets yet, the show
   waits through the dusk for them." (README, What the player does). Proof: csfullcycle base (31 Dec and 1 Jan "the
   dusk show goes up from the Decorator's stock", "midnight: one midnight show", the countdown, the rocket stock
   dropping by exactly what flew); emptywarehouse ("a dusk with no rockets notes nothing; rockets put in the hut during it
   give that dusk's show").
17. The Decorator hosts on festival days: stands at the tree, hands out gifts, sets off fireworks.
   Proof: csfestival; csfullcycle base ("the Decorator hands colonists gifts", Hosting counted once a visit, the dusk
   shows from his stock).
18. "Our own" sounds: the README says the textures, models and sounds are our own; check that every sound
    in the jar is ours and that it is made by `tools/`.
   Proof: checks.sh "festival" (tools/check_festival.py: every sound has its file, made by tools/sfx).

Notes from S7 (19 Nov 2026, probe `csfestival`, all modes and `tools/restart.sh csfestival` PASS):
- 12: proven; only a player of the colony gets a gift (MineColonies' colony protection refuses a visitor's click), and
  the claim is kept for the whole server, so a second pile or another colony's never gives a second one.
- 13: proven; likewise only a player who may use the colony's blocks lights the tree.
- 14 and 15: proven, nomixin mode included (only off-duty colonists come). Off-duty colonists on MineColonies' own
  leisure trip elsewhere have the trip ended so they come.
- 16: proven with a fake clock; the dusk show is the first dusk of each real day with a player in the colony.
- 17: proven for the dusk shows. The Decorator stands at the tree and hands out gifts; on the New Year days he goes to
  the square and gets the rockets in, and on the evening the mixin keeps him up past bedtime until he has launched the
  dusk show there (then he goes to bed). Without the mixin (or when he is ill, hungry or it rains) the server launches
  it from the square with his rockets. The midnight show comes at a real hour and always goes up from the server, so
  "sets off the fireworks" holds for the dusk shows; say so if the page names the midnight show.
- 18: the two festival sounds are made by `tools/sfx/*.py`.
- 23: built in S7 (`festival/FestivalDirector`), proven by csfestival.

## Rests on S8 (effects, hut window)

19. The three effect switches do what the README table says and are off by default; the strengths are in the
    config.
   Proof: cseffects off and on; csfullcycle effects (all three on through the cycle: the bonus only on festival days, crops
   and food only in winter; then switched off through the config spec: a pass later nothing is left).
20. The happiness bonus is counted but not listed in the citizen window; the Decorator's hut window shows
    it (design 8). If the fallback of reusing MineColonies' own id was needed, the text must say so.
   Proof: cseffects on (the client's total equals the server's; our id is not in the citizen window's list).
21. MineColonies' own crops probably ignore the winter slowdown; vanilla crops inside a claim do not. State
    what the in-game test showed.
   Proof: cseffects on ("[V] MineColonies' own crop block grows, and does not fire CropGrowEvent.Pre").
   S10: state what the in-game test shows.
22. The hut window lists missing materials and shows the season and day.
   Proof: cseffects on (the hut window page: season and day, missing materials). The page now follows the server while
   it is open and wraps a long line into rows (S9).
   S10: open the hut window, watch a status change while it is open, read a long line (a long item name in the missing list).
   Changed (S11b): the README lists the line of the day's buildings and puts the Discord / Support line under the list,
   always in view (S10h); the Settings page has five switches besides "Prepare all year", not four.
23. One chat line to the colony's players when the season turns (design 2.2; not in S1).
   Proof: csfestival ("the season turns: one line to the colony's players").

## Rests on S9 and S10 (not a feature, a claim)

24. Every number in the pages: 48-day year, twelve days a season, about four hours of play a season, the dates, the
    levels 1 to 5, the three switches, eleven blocks.
   Proof: csconfig defaults (48 days, the dates, the three switches off); cscalendar dates (twelve days a season); csblocks
   (the eleven blocks); csdecorgate setup (levels 1 to 5). Four hours: twelve game days of twenty minutes.
25. Requirements line: NeoForge 1.21.1, MineColonies 1.1.1420 or newer in the 1.1 line, Structurize, Domum
    Ornamentum required, Serene Seasons optional, install on the server and on every client.
   Proof: csboot (MineColonies 1.1.1420, the lower bound of the range); the mods.toml dependencies (Structurize, Domum
   Ornamentum required, Serene Seasons optional); tools/packboot.sh (20 Nov 2026: the owner's pack, 186 of its 211 jars
   on the rig's NeoForge 21.1.249 dedicated server, with and without the jar: no new ERROR naming the mod, no crash; the
   other 25 are ten client-side mods, Curios, which needs NeoForge 21.1.265 or newer, and fourteen mods that need it).
   S10: the client side (scarves, the window) in the real client.
26. Serene Seasons: follows its calendar when present, goes back to the own calendar on any error with one
    log line (S1 probe `cscalendar` proved the bridge; recheck against the real mod if the in-game test uses
    it).
   Proof: cscalendar bridge-present, bridge-throws, bridge-nonseasonal, bridge-off; csfullcycle serene (the whole cycle on
   the stand-in: the season follows it every day, the festivals follow the dates).
   S10: recheck against the real Serene Seasons if the in-game test uses it.
27. Without a Decorator's Workshop: clothes and the season line still work, and on the festival days the off-duty
    colonists gather at the festival marker if one stands, else at the town hall; no tree, gifts or fireworks.
   Changed: README ("No Decorator, no decorations" in What it does not do) and the CurseForge description ("What it does
   not do") now say where they gather. Proof: csfullcycle nodecorator (the festive scarf, nothing placed, a player's pile
   hands out nothing, the gathering at the marker, at the town hall after the marker is broken, no show) and nomarker
   (the gathering at the town hall, no square, no show).
28. `season set` with Serene Seasons present changes nothing and says so (built in S1, in the lang file).
   Proof: cscalendar bridge-present ("season set says Serene Seasons decides and does not claim a change").
29. The GitHub and CurseForge links in the pages and in mods.toml work once the repository and the project
    exist (they do not yet).
   S10 and the upload: the repository and the project do not exist yet.
   S11b: the CurseForge project exists (`release/curseforge.md`). Before the upload, open
   https://github.com/Lovkar-Squid/colony-seasons logged out: a private repository is a dead link for every visitor,
   and then the GitHub line of the page and the mods.toml URLs wait until it is public.

## Added by S9

30. The gift pile and the firework crate come with the finished tree: they (and at level 5 the large star and the
    tinsel leaves) go up last, once every log, leaf, ornament and the star the Decorator pays for is in place.
   Changed: README (the Decorator's level table, the sentence below it). Proof: hostile-world H8 (no stock: nothing
   placed; logs only: the trunk and nothing free); csfullcycle emptywarehouse (nothing ever placed).
   Changed (S10c): README "once the tree's own trunk, leaves, ornaments and star are in place"; a paid cell the cell
   rule refuses for good (a lantern whose planter brick cell holds grass) no longer holds the free ones back.
   Proof: csdecorgate square ("the grand tree with short grass on a planter cell").
31. With no rockets in his stock the New Year dusk waits for them and no show is counted; see item 16. Proof:
   csfullcycle emptywarehouse.
32. The Decorator takes a big tree down over a few visits, at most 256 blocks a visit. Not in the pages (a detail).
   Proof: hostile-world H9 (the grand tree, 375 blocks: 256 then 119, the square equal to its snapshot after) and H9c (a
   slice boundary at every cell: the square equal to its snapshot, every paid item back once, nothing dropped).

## Added by S10b

33. README: a new Workshop gets its worker in MineColonies' usual order (the oldest hut with a free place first),
    so hire the Decorator in its window while other huts have free places; there a colonist who works at another
    hut can be hired however far away that hut is, and he leaves his old job.
    Proof: cshire (L1 and L5 auto-hire with the Workshop as the only free place; the far moves of a knight and a
    lumberjack with the player at the Workshop; the control: the same far move into a MineColonies hut fails) and
    `tools/restart.sh cshire`. MineColonies ticks its buildings in the order they were added (its building map is
    rebuilt with the new hut last), which cshire logs. S10 retry: hire a fletcher or a knight from the Workshop.

## Added by S10c

34. README: the trunk looks like spruce logs but is the mod's own block, so a Forester never takes the festival tree
    for a tree to cut.
    Proof: csforester (the trunk in no log tag at runtime, MineColonies' own tree test says no tree at every trunk
    cell, the Forester cuts a natural oak and leaves the festival tree whole for three game days); `tools/build_pack.py`
    (no log block in any piece). MineColonies' tree test also turns the tree down for its persistent leaves, so the
    trunk block is what keeps it safe from anything that cuts `#minecraft:logs`.
35. README: colonists standing where the tree goes are moved aside first, and he never builds round a player or an
    animal; a cell of the square that cannot be filled for now does not hold him up: after a few tries he goes on to
    the other buildings and tries again the next day, and the tree is ready to light as soon as the tree itself stands.
    Proof: csfestival (the square boxed in: nobody in a listed block, the colonist moved out, another building the same
    game day; the square held by an armor stand in a gift cell: the cell rule says "entity", left for the day after 3
    visits, the tree ready and lit by the Decorator two game days later); csdecorgate square (grass on a planter cell).
    S10 retry: watch the Decorator build the grand tree and go on to the other buildings the same game day.

## Built already and shown in the pages

S1: the calendar, the dates, `status`, `season set`, `festival force`, `festival clear`, the world-wide
calendar, the start season from the real date, the Serene Seasons bridge. S2: the eleven blocks, the creative
tab, crafting recipes. S3: the Decorator, hut, job, materials, `decorator.needsMaterials`, kits as datapack
files, the prepare-all-year setting. S6: the scarf colours, the wool hat rules, the festive scarf, no scarf over
a chestplate, the client config.

## Pictures the CurseForge page should get (S10)

Screenshot slots, in this order: the square with the tree at dusk and the lights on; a street of decorated
houses; the Decorator's Workshop; the Decorator at work; the gathering at the tree;
New Year fireworks over the colony; colonists in scarves and hats (the six-colour line-up); the hut window.

S11b: the description has no image links, on purpose. CurseForge shows a picture in the description only from an
absolute link, and the screenshots go into the project's gallery, uploaded by hand. Nothing in the page names a
picture that does not exist. Renders that exist and could go to the gallery as renders (not screenshots):
`docs/renders/s10d/2026-11-20-s10d-after-square-day.png` and `-night.png` (the square and two houses),
`docs/renders/s5/2026-11-19-s5-hut-levels.png` (the five Workshop levels), `docs/renders/s5/2026-11-19-s5-tree-grand.png`,
`docs/renders/s6/s6-colours-2026-11-19.png` (the scarves), `docs/renders/s7/2026-11-19-s7-gifts.png`,
`docs/renders/s10g/2026-11-21-s10g2-l5-festival.png` (the storeroom in the festival).
