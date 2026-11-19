# What the final pass must check

The page texts (`README.md`, `release/curseforge-description.md`, `CHANGELOG-0.1.0-beta.1.md`, the mods.toml
description) were written from `docs/design.md` while S4, S5, S7 and S8 were still being built. After S9 and the
in-game test (S10), go through this list. For each line, find the proof (a probe result, the S9 report or the
in-game test), and change the sentence in all the files that carry it if the result differs. Delete a line from
the page rather than keep a sentence nobody proved.

State when this list was made: S0, S1, S2, S3 and S6 merged; S4, S5, S7 and S8 not yet.

## Rests on S4 (placing, ledger, take-down)

1. The Decorator places a wreath on the main door and, by level, garland and lights beside it. Amounts by level
   in the README table (design 3.4).
2. He places only into air, in loaded chunks, inside his own colony's claim.
3. He writes every placed block to a list saved with the world and takes away only a block on that list that
   still has the recorded state; blocks built by players or cleared by the Builder are never touched.
4. A building under construction or upgrade is skipped and its decorations come off first.
5. With no Decorator when the window ends, the server takes the decorations down itself as chunks load.
6. A building with no free wall gets nothing; the building keeps its kind of lights.
7. `/colonyseasons decor list` and `decor remove <colony>` exist and work as the README says (design 3.3).
8. After the window he takes down what he put up and keeps the items in his hut.
9. The festival marker: the tree stands where it is placed, facing as the marker faces; with at most a tenth
   of the cells blocked (and no trunk cell) he builds the rest, otherwise nothing and a note in the hut window.
10. Uninstalling: `decor remove` first; the tree's logs and leaves stay (design 3.3).

## Rests on S5 (hut and tree blueprints)

11. The Workshop can be built with the build tool, levels 1 to 5, and the tree sizes match the table: small
    village spruce at levels 1 and 2, full size at 3 and 4 (more ornaments at 4), the grand tiered tree with
    the large star at level 5. The gift pile at level 2 and the firework crate at level 3.

## Rests on S7 (festival events)

12. Gifts: one small gift a day per player per real day from the first day of the window, a better gift on
    24 to 26 December, the gift of New Year days holds a few rockets. The gift opens into a roll of
    `colony_seasons:gifts/winter_daily` (and `gifts/winter_festival`) and a pack can replace the tables.
13. Lighting the tree: the colony's players are told when it is finished; a right-click lights the ornaments,
    star and strings with a chime and a few rockets; the Decorator lights it after two game days.
14. The gathering on 24 to 26 December in the afternoon of the game day (ticks 8500 to 10500 by default), a
    short tune plays, the Decorator hands out gifts.
15. `events.whoComes`: default everyone once per real day, off-duty colonists on the other game days; the
    mixin fails quietly if MineColonies moves its method.
16. Fireworks at dusk on New Year, a countdown and a big show at midnight if the server runs through it
    (`events.midnightShow`).
17. The Decorator hosts on festival days: stands at the tree, hands out gifts, sets off fireworks.
18. "Our own" sounds: the README says the textures, models and sounds are our own; check that every sound
    in the jar is ours and that it is made by `tools/`.

Notes from S7 (19 Nov 2026, probe `csfestival`, all modes and `tools/restart.sh csfestival` PASS):
- 12: proven; only a player of the colony gets a gift (MineColonies' colony protection refuses a visitor's click), and
  the claim is kept for the whole server, so a second pile or another colony's never gives a second one.
- 13: proven; likewise only a player who may use the colony's blocks lights the tree.
- 14 and 15: proven, nomixin mode included (only off-duty colonists come). Off-duty colonists on MineColonies' own
  leisure trip elsewhere have the trip ended so they come.
- 16: proven with a fake clock; the dusk show is the first dusk of each real day with a player in the colony.
- 17: differs. The Decorator stands at the tree and hands out gifts, and on the New Year days he goes to the square and
  gets the rockets in; but dusk is after the colonists' bedtime, so the server launches the shows from the firework
  crate with the rockets of his stock while he sleeps. "Sets off fireworks" needs rewording unless that changes.
- 18: the two festival sounds are made by `tools/sfx/*.py`.
- 23: built in S7 (`festival/FestivalDirector`), proven by csfestival.

## Rests on S8 (effects, hut window)

19. The three effect switches do what the README table says and are off by default; the strengths are in the
    config.
20. The happiness bonus is counted but not listed in the citizen window; the Decorator's hut window shows
    it (design 8). If the fallback of reusing MineColonies' own id was needed, the text must say so.
21. MineColonies' own crops probably ignore the winter slowdown; vanilla crops inside a claim do not. State
    what the in-game test showed.
22. The hut window lists missing materials and shows the season and day.
23. One chat line to the colony's players when the season turns (design 2.2; not in S1).

## Rests on S9 and S10 (not a feature, a claim)

24. Every number in the pages: 48-day year, twelve days a season, about four hours of play a season, the dates, the
    levels 1 to 5, the three switches, eleven blocks.
25. Requirements line: NeoForge 1.21.1, MineColonies 1.1.1420 or newer in the 1.1 line, Structurize, Domum
    Ornamentum required, Serene Seasons optional, install on the server and on every client.
26. Serene Seasons: follows its calendar when present, goes back to the own calendar on any error with one
    log line (S1 probe `cscalendar` proved the bridge; recheck against the real mod if the in-game test uses
    it).
27. Without a Decorator's Workshop: clothes and the season line still work; no tree, gifts or fireworks.
    Check whether off-duty colonists still gather near the town hall (design 6) and say so only if proven.
28. `season set` with Serene Seasons present changes nothing and says so (built in S1, in the lang file).
29. The GitHub and CurseForge links in the pages and in mods.toml work once the repository and the project
    exist (they do not yet).

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
