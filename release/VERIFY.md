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
10. Uninstalling: `decor remove` first; the tree's logs and leaves stay (design 3.3).
   Proof: hostile-world H18: decor remove in the middle of the festival leaves nothing listed, the square as it was before
   the tree and every paid item back in the hut; without it, the tree's 122 vanilla logs and leaves (village spruce, full
   size) would stay. The README sentence holds as written.

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

## Added by S9

30. The gift pile and the firework crate come with the finished tree: they (and at level 5 the large star and the
    tinsel leaves) go up last, once every log, leaf, ornament and the star the Decorator pays for is in place.
   Changed: README (the Decorator's level table, the sentence below it). Proof: hostile-world H8 (no stock: nothing
   placed; logs only: the trunk and nothing free); csfullcycle emptywarehouse (nothing ever placed).
31. With no rockets in his stock the New Year dusk waits for them and no show is counted; see item 16. Proof:
   csfullcycle emptywarehouse.
32. The Decorator takes a big tree down over a few visits, at most 256 blocks a visit. Not in the pages (a detail).
   Proof: hostile-world H9 (the grand tree, 375 blocks: 256 then 119, the square equal to its snapshot after) and H9c (a
   slice boundary at every cell: the square equal to its snapshot, every paid item back once, nothing dropped).

## Added by S10b

33. README: a new Workshop gets its worker in MineColonies' usual order (the oldest hut with a free place first),
    so hire the Decorator in its window while other huts have free places; a colonist who works elsewhere can be
    hired from anywhere in the colony and leaves his old job.
    Proof: cshire (L1 and L5 auto-hire with the Workshop as the only free place; the far moves of a knight and a
    lumberjack with the player at the Workshop; the control: the same far move into a MineColonies hut fails) and
    `tools/restart.sh cshire`. MineColonies ticks its buildings in the order they were added (its building map is
    rebuilt with the new hut last), which cshire logs. S10 retry: hire a fletcher or a knight from the Workshop.

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
The description has no image links yet; add them when the files exist.
