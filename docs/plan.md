# Colony Seasons 0.1.0: chunk plan

Written 19 Nov 2026. Design: `docs/design.md` (its [B] rules bind every chunk). Research: `research/*.md` (local notes with machine paths; they stay out of the public repo). Nothing is built yet.

**Approved by the owner on 19 Nov 2026:** the plan as written, a usage cap of 30 points of the weekly meter for the build, a local `git init` in `D:\Claude\colony-seasons`, start on Fri 20 Nov 2026. Past 30 points: stop and ask him with a concrete new figure.

## How it is built

- /ekipa-agentov, one chunk per run, each in its own worktree, an independent verify before anything merges. `judgeModel: "opus"`. No Fable agents; the lead session runs on Opus 5.5 with a fresh, small context.
- Builders: Sonnet 5.5 where the chunk is well specified and has a working template in Voyager; Opus 5.5 where the look or MineColonies' behaviour decides the result (reason given per chunk).
- Rigor "high" (extra audit and a fix round budgeted) only on the three chunks that can damage a player's world or hide a crash: S3, S4, S9. The in-game test S10 is done by the lead.
- Build: plain javac + jar against `D:\Claude\voyager-libs` plus the MineColonies 1.1.1420 set, through the shared rig `D:\Claude\addon-rig` (`build-addon.ps1 -Repo <clone> [-Probe <name>]`, `run-rig.ps1 -Name <probe> -Jars ...`, dedicated NeoForge 21.1.249 server, fresh flat world). A probe is a tiny server-side mod that logs `CHECK ...: OK|FAIL` and `RESULT ...: PASS|FAIL`.
- Jar rules: build with `jar`, never patch a jar; verify by a sequential read before any upload; one version, one jar.
- A git repository is needed for worktrees. The owner gave his OK for a local `git init` in `D:\Claude\colony-seasons` on 19 Nov 2026; S0 does it, after `.gitignore` keeps `research/` out, and decides whether the large renders in `design/` are committed or only the four sheets and `design/src/`. Commit identity `Lovkar <Lovkar-Squid@users.noreply.github.com>`. Nothing is pushed before his "objavi".
- Every chunk ends with: build green, its probes PASS, static checks (models, lang keys, block ids) "ALL OK", a line in `docs/CHANGELOG.md`.

## Order

```
S0 ─┬─ S1 ─┬─ S6 ──────────────┐
    │      └─ S8 (needs S3) ───┤
    ├─ S2 ─┐                   │
    ├─ S3 ─┴─ S4 ─┬─ S7 ───────┼─ S9 ─ S10 ─ upload
    └─ S5 ────────┘            │
S11 (any time after the owner's look answers) ┘
```

S2, S3 and S5 touch different files and can run side by side after S0. S4 waits for S2 and S3. S7 waits for S4 and S5.

## Chunks

### S0. Skeleton, build, first boot
- **Goal:** an empty mod that loads next to MineColonies on the rig.
- **Files:** `resources/META-INF/neoforge.mods.toml`, `src/me/lovkar/seasons/ColonySeasons.java` (registries, buses), `SeasonsConfig.java` with the full key list of design section 9 and default constants, `stubsrc/`, `BUILDING.md`, `.gitignore` (`research/`, `libs/`, `build/`, `stubs/`, jars), `LICENSE`, `README.md` stub, `tools/rigboot/`, `tools/configcheck/`, `tools/check_lang.py`, `tools/check_models.py` (copied from the Voyager and Colonies at War repos).
- **Tests:** `rigboot` (mod loads, server ticks, no ERROR naming the mod); `configcheck` in both modes (defaults against a hand-written list; a seeded file read back).
- **Done when:** both probes PASS on MineColonies 1.1.1420; the jar reads sequentially; the lower bound of the MineColonies version range is the oldest version the boot was run on.
- **Out of scope:** any block, the hut, the calendar logic.
- **Builder:** Sonnet. **Rigor:** normal. **Cost:** 1.5.

### S1. Calendar
- **Goal:** the server knows the season and the active festivals, saves them, tells clients, and obeys commands.
- **Files:** `calendar/` (`DateSource`, `Festival`, `FestivalCalendar`, `SeasonCalendar`, `SeasonState`, `CalendarData`, `SereneBridge`), `network/SeasonStatePayload.java`, `client/ClientSeasonState.java`, `command/SeasonsCommands.java` (`status`, `season set`, `festival force|clear`), lang keys, `tools/calendarcheck/`, a 20-line stand-in jar `sereneseasons.api.season.*` for the bridge test (written here, not downloaded).
- **Tests:** `calendarcheck`: with a fake `DateSource`, each window opens and closes on the right dates including the year turn (31 Dec to 1 Jan) and 29 Feb; season from day time and offset, year lengths 4, 48 and 96; `startSeason = auto` for four dates; state survives a server restart; force and clear; bridge absent, bridge present (stand-in jar), bridge throwing falls back with one log line.
- **Done when:** all checks PASS and a client-side unit check decodes the payload.
- **Out of scope:** anything that reacts to the calendar.
- **Builder:** Sonnet. **Rigor:** normal. **Cost:** 1.5.

### S2. Decoration blocks and items
- **Goal:** the twelve blocks of design 3.1 exist (wreath, garland, lantern string, fairy lights, ornament leaves, tinsel leaves, tree star, large star, gift pile, firework crate, festival marker; the hut block comes in S3), look right and can be crafted and placed by hand.
- **Files:** `block/` (wall blocks with facing, `OrnamentLeavesBlock`, `TreeStarBlock`, `GiftPileBlock` + block entity, `FireworkCrateBlock`, `FestivalMarkerBlock` + block entity), items, `resources/assets/colony_seasons/{blockstates,models,textures}`, loot tables, recipes, tags, lang, `tools/textures/*.py` (textures are generated by script from the chosen variants in `design/src/`), creative-tab entries.
- **Tests:** `check_models.py`, `check_lang.py`; `blockcheck` probe: each block places in each facing, has no collision where the design says so, drops its item, wall blocks pop off when the wall behind is removed; renders of each block on a wall with `design/tools` for a look check by the lead.
- **Done when:** probes PASS and the renders match the variants the owner picked (wreath A, strings A, B and C), with the fairy-light bulbs and the baubles enlarged so they read from 20 blocks away, and one night render that shows the lit blocks.
- **Out of scope:** the ledger, automatic placing, gift logic beyond the block entity's saved fields.
- **Builder:** Opus (16x16 pixel art and block models decide how the whole mod looks). **Rigor:** normal. **Cost:** 2.

### S3. The Decorator: hut, job, daily loop
- **Goal:** a colony can build the hut, hire a Decorator, and he asks for materials, assembles decorations and keeps stock.
- **Files:** `block/BlockHutDecorator.java`, `block/SeasonsTileEntity.java`, `colony/BuildingDecorator.java`, `colony/DecoratorModules.java` (worker, settings, stock), `colony/JobDecorator.java`, `ai/EntityAIWorkDecorator.java` (states and the phase table of design section 4, with stubs where S4 and S7 plug in), `decor/Kits.java` + `data/colony_seasons/decorator_kits/*.json`, citizen model and skins (`tools/gen_decorator_skin.py`; textures under `assets/minecolonies/...`, icons in all eight style folders), the `CITIZEN_SOUND_EVENTS` entry, hut block model, recipe, loot table, lang, a one-level placeholder blueprint until S5.
- **Tests:** `decoratorjob` probe on a real colony (the `colonytest` pattern): hut placed and levelled, citizen assigned, a server day driven; checks: no exception for 3 game days including a night and forced rain (AI resets), a request for kit materials appears, materials put in the hut become decoration items, stock stops at the cap, state survives a restart.
- **Done when:** the probe PASSes and the log holds no ERROR or repeated WARN from MineColonies about the job, its sounds or its model.
- **Out of scope:** walking to buildings, placing, events.
- **Builder:** Opus (a MineColonies job fails in ways only its own code explains: voice lines, module identity, AI resets). **Rigor:** high. **Cost:** 3.5.

### S4. Placing, the ledger, taking down
- **Goal:** the Decorator decorates buildings and the square by the safety rules, and removes exactly what he placed.
- **Files:** `decor/DecorLedger.java`, `DoorFinder.java`, `DecorPlanner.java`, `DecorPlacer.java`, `PieceLoader.java`, the DECORATE / TEND / TAKE_DOWN states in the AI, `command` (`decor list`, `decor remove`), the building-removed and pending-construction handling, `tools/decorgate/`.
- **Tests:** `decorgate` probe. It pastes real hut blueprints from at least six MineColonies style packs plus hand-made edge cases (door under an overhang, double door, no door, a player wreath already above the door, a torch in the garland row, a building at the claim edge), snapshots the world, runs decorate, then take-down, and checks: every changed cell was air before and is in the ledger; nothing else changed; after take-down the world equals the snapshot cell for cell; a block the "player" swapped mid-festival is left alone and its entry forgotten; the player's own wreath survives; a hut upgrade in mid-festival neither crashes nor leaves ledger ghosts; hut destroyed mid-festival then window ends: the server sweep clears everything; `decor remove` clears a colony in one go; restart in the middle of decorating resumes without double placing. The probe also writes per-building counts, so the lead can see which styles get few decorations.
- **Done when:** every check PASSes and at least four of six styles get a wreath on most huts (if not, the door rule is revised before merge).
- **Out of scope:** the events at the tree, clothes.
- **Builder:** Opus (this is the part that touches players' builds; the rules are exact and the edge cases many). **Rigor:** high. **Cost:** 4.

### S5. Blueprints: the hut and the tree
- **Goal:** the Decorator's hut (look A, workshop with shopfront) in five levels and the tree pieces (village spruce small and full size, grand tiered tree for level 5), in our own pack.
- **Files:** `tools/voxel.py`, `tools/build_pack.py`, `tools/decorator_hut.py`, `tools/tree_pieces.py`, `tools/floatcheck.py`, `tools/walkcheck.py` (copied from the Voyager repo and adapted), `resources/blueprints/colony_seasons/colony_seasons/{pack.json, icon, decorator/*.blueprint, pieces/*.blueprint}`, `tools/pastecheck/`.
- **Tests:** build script prints "ALL OK" (ids against the client jar and our blockstates, no floating blocks, walkable, hut block reachable, round trip of every file); `pastecheck` pastes every file through Structurize and reads the world back; renders of level 1, 3 and 5 and of the three trees for the owner.
- **Done when:** checks PASS and the lead has compared the renders with the picked variants.
- **Out of scope:** more than one hut look; pieces for other festivals.
- **Builder:** Opus (building design in five levels that must look like the picked picture). **Rigor:** normal. **Cost:** 2.5.

### S6. Clothes
- **Goal:** colonists wear a scarf (and a wool hat where it fits) in winter and a festive scarf in the winter window, on every client, with nothing saved on the colonist.
- **Files:** `client/SeasonsClient.java`, `client/ClothesLayer.java`, `client/ScarfModel.java`, `client/WoolHatModel.java`, `assets/colony_seasons/textures/entity/*.png` (`tools/gen_clothes.py`), `assets/colony_seasons/clothes/hat_models.json` (allow-list), `SeasonsClientConfig.java`.
- **Tests:** static checks; preview renders on at least eight job models, male, female and child, made with the Voyager entity preview tool; `rigboot` still PASSes on a dedicated server (no client class touched there); a short client-side check that the hat is skipped when MineColonies' December hat shows.
- **Done when:** previews show no scarf through a face or floating off a child, and the dedicated server boots.
- **Out of scope:** costumes for other festivals, clothes for players.
- **Builder:** Opus (fitting one layer onto about forty models is a look problem). **Rigor:** normal. **Cost:** 2.

### S7. Festival events
- **Goal:** the tree can be lit, gifts can be taken, colonists gather, New Year has its fireworks.
- **Files:** `festival/FestivalDirector.java` (per colony, on colony ticks), `Gathering.java`, `Gifts.java` (gift item, loot tables `data/colony_seasons/loot_table/gifts/*.json`), `TreeLighting.java`, `Fireworks.java`, the HOST and LAUNCH states in the AI, sounds (`tools/sfx/*.py`, synthesized), lang, `tools/festivalrun/`. `mixin/CitizenAIFestivalMixin.java` + `colony_seasons.mixins.json`, soft-fail (the owner chose "everyone once per real day").
- **Tests:** `festivalrun` probe with forced dates: tree lit by a fake player and by the Decorator after two days; one gift per player per real day, a second try refused, the claim survives a restart; gathering: at least the off-duty colonists stand within 8 blocks of the tree during the hours and are back at work or in bed afterwards; nobody is stuck awake past bedtime; fireworks spawn at dusk and use stock; the midnight show with the fake clock stepping over midnight; with the mixin removed from the jar the mod still loads; with Voyager installed next to it both mixins apply and the Astronomer still works at night.
- **Done when:** the probe PASSes and three game days of a forced festival run with no ERROR.
- **Out of scope:** players giving gifts to colonists; a colony event entry.
- **Builder:** Opus (it steers MineColonies' citizen AI from outside, the least documented part). **Rigor:** normal. **Cost:** 3.

### S8. Effects (off by default) and the hut window
- **Goal:** the three switches work when on and do nothing when off; the hut window shows the calendar, progress and what is missing.
- **Files:** `effects/HappinessEffect.java`, `CropEffect.java`, `FoodEffect.java`, the info module and its view in `colony/DecoratorModules.java` (+ BlockUI xml), settings (decorate on/off, which kinds), lang, `tools/effectscheck/`, `tools/check_packets.py`.
- **Tests:** `effectscheck`: all off: no modifier on any colonist, growth events untouched, saturation as before over a game day; each on: modifier present and gone after the festival, about half the growth steps vetoed inside a claim and none outside, extra food use within 10 % of the target; the happiness total shown to a client still equals the server's (design 8 [V]); `check_packets.py` on the module view.
- **Done when:** PASS, and the README text for the two known limits is written.
- **Out of scope:** new effects, balance tuning.
- **Builder:** Sonnet. **Rigor:** normal. **Cost:** 1.5.

### S9. The whole cycle, a hostile review, fixes
- **Goal:** find what the chunk tests missed before a player does.
- **Files:** `tools/fullcycle/` and whatever the fixes touch.
- **Tests:** `fullcycle` probe: a colony with twelve buildings and a marker, the fake clock run from 28 Nov to 10 Jan with restarts in between: decorations up, tree lit, gifts, gathering, fireworks, take-down, world equal to the first snapshot; the same with no Decorator, with no marker, with empty warehouse, with all effects on, and with the stand-in Serene Seasons. Then a review by agents told to break it (dupes through the gift pile and kits, ledger growth, chunk-unload timing, two colonies side by side, a second marker, removing the mod), each finding reproduced on the rig before it is fixed. A boot of the full modpack set with the jar added (the pack's headless boot script) to catch clashes with other mods.
- **Done when:** every scenario PASSes, every confirmed finding is fixed or listed for the owner, and the pack boot shows no new ERROR.
- **Out of scope:** new features.
- **Builder:** Opus (adversarial reading and root-cause fixes). **Rigor:** high. **Cost:** 4.

### S10. In-game test and media
- **Goal:** proof in the real client, and pictures and a clip for the page and Discord.
- **Steps:** the handoff's delivery rules (game closed, one jar, hashes compared, restore note); the pack dev instance and its TEST world, forced festival; build the hut, hire the Decorator, place the marker, watch a full decorate, light the tree, take the gift, see the gathering and the fireworks, run take-down; read `latest.log`; screenshots and a 30 to 40 second clip; behind-the-scenes shots with captions; then restore the instance and check its manifest lists every mod again.
- **Done when:** no error from the mod in the log, the owner has the media, the instance is restored.
- **Out of scope:** publishing.
- **Builder:** the lead (Opus), one helper agent for media. **Rigor:** high. **Cost:** 3.

### S11. Page, logo, README, changelog (drafts for the owner)
- **Goal:** everything public is ready for his yes.
- **Files:** `README.md`, `docs/CHANGELOG.md`, `CHANGELOG-0.1.0-beta.1.md`, `release/curseforge-description.md`, `release/summary.txt`, `tools/gen_logo.py` and the logo (400 px), two or three logo variants as pictures.
- **Tests:** no personal names, paths or world names anywhere in the repo (a grep in the build script); the description matches what S9 proved, including the limits.
- **Done when:** he has read and approved the exact text and the logo.
- **Out of scope:** creating the CurseForge project or pushing to GitHub (both on his word only).
- **Builder:** Sonnet for text, Opus for the logo. **Rigor:** normal. **Cost:** 1.

## Cost

Points of the weekly meter, all sessions share it. The figures come from this week's and last week's meter readings, not from token maths: a normal chunk of his mods cost 1 to 3 points, a high-rigor one 3 to 5 (17 and 19 Nov 2026).

| Chunk | Model | Rigor | Points |
|---|---|---|---|
| S0 skeleton | Sonnet | normal | 1.5 |
| S1 calendar | Sonnet | normal | 1.5 |
| S2 blocks | Opus | normal | 2 |
| S3 hut and job | Opus | high | 3.5 |
| S4 placing and ledger | Opus | high | 4 |
| S5 blueprints | Opus | normal | 2.5 |
| S6 clothes | Opus | normal | 2 |
| S7 events | Opus | normal | 3 |
| S8 effects and window | Sonnet | normal | 1.5 |
| S9 full cycle and review | Opus | high | 4 |
| S10 in-game and media | lead | high | 3 |
| S11 page and logo | Sonnet/Opus | normal | 1 |
| **Total** | | | **about 30 (range 22 to 36)** |

- The design round cost about 1 point: four research agents, one agent for the pictures, and the lead. The meter went from 24 % to 29 % in those two hours, but about four fifths of that was other sessions (counted from the transcripts: 52 of 254 weighted units, so about 50 units a point).
- That measurement and the mod chunks of 17 Nov point to the low end of the range (about 22); the DewPoint night of 19 Nov points to the high end. The cap is asked for the high figure, and the gate below tells which it is.
- **Gate:** the meter is read before and after S0 and S1. If the two together cost more than 4 points, work stops there and the owner gets new figures before S2 starts.
- If the meter runs hot, these could move to 0.1.1 in mid-December without breaking 0.1.0: the gathering of working colonists (the mixin), the midnight show, the winter crop and food switches. That would save 3 to 4 points. The first two are choices the owner made, so they move only on his word.
- The earlier guess of 15 to 20 points for the whole mod was made before this week's measurements; it was too low.

## Schedule

The weekly meter resets on Wed 25 Nov 2026 at 22:59 (Europe/Ljubljana). A new CurseForge project takes days to approve, so the first file goes up by Fri 27 or Sat 28 Nov.

| Day | Work | Points |
|---|---|---|
| Thu 19 Nov | design and plan; his approval, cap, look answers | (1, spent) |
| Fri 20 Nov | S0, S1, the gate; S2 and S5 start | 3 + part of 4.5 |
| Sat 21 Nov | S3; S2 and S5 finish | 8 |
| Sun 22 Nov | S4, S6 | 6 |
| Mon 23 Nov | S7, S8; S11 drafts to him | 5.5 |
| Tue 24 Nov | S9 | 4 |
| Wed 25 Nov | spare day for fixes; reset at night | 0 to 2 |
| Thu 26 Nov | S10 in the client; candidate jar in the dev instance for him | 3 |
| Fri 27 Nov | on his "objavi": GitHub repo public, CurseForge project created, `0.1.0-beta.1` uploaded | under 1 |
| about 1 to 3 Dec | approval; `0.1.0` with fixes from the first days; project id and file id to him for the modpack side | 1 to 2 |

About 26 points fall in this week and about 4 to 6 in the next. Other work shares the meter (it read 29 % when this was written). His gaming pauses and a hot PC can cost a day; the spare day is for that. If the first upload slips past 28 Nov, the mod is still public in the first week of December, and the Decorator starts on the day it is installed.

To let the project review start early, the CurseForge project can be created as soon as he approves the page text and logo (Mon 23 Nov at the earliest), before the jar is ready. Whether CurseForge reviews a project that has no file yet must be checked in the Authors console at that moment.

## What needs the owner

1. (Done 19 Nov 2026: plan approved.)
2. (Done: cap 30 points.)
3. (Done: local `git init` allowed.)
4. (Done 19 Nov 2026: the eight look and feel answers are in design section 12.)
5. Later: the page text and logo (S11), "objavi" for GitHub and CurseForge.
