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

State after S10i (21 Nov 2026, branch ekipa-s10i): the owner's answers of 21 Nov 2026 (design, last section). The two
findings of S11b below are fixed in the code: `showParticles` is gone from the client config, and the Calendar page
says when the tree has no room. The CurseForge page was approved as written and named neither, so it is unchanged.

## The CurseForge page, claim by claim (S11b)

`release/curseforge-description.md`: about 8,200 characters, no images (CurseForge needs absolute image links, and
the screenshots go into the project's gallery, see the end of this file).

| # | Claim on the page | Proof | Look |
| --- | --- | --- | --- |
| P1 | Seasons run in the game, festivals on real dates | `calendar/CalendarManager.java:96-113` (the season from the bridge or `SeasonCalendar.state`, the festivals from `DateSource.today()`); cscalendar dates | |
| P2 | In December he hangs wreaths, garlands and lights and builds a tree on the square | `decor/DecorPlanner.java:84-93` (amounts by level), `decor/PieceLoader.java:107-114` (the tree per level); csdecorgate styles, csfullcycle base | yes: a real colony decorated (item 1) |
| P3 | Striped scarves, the gathering on the Winter Festival days (24 to 26 December), a small gift a day, New Year fireworks | `client/ClothesRules.java` (`outfit`), `festival/Gathering.java:82,140-151` (`FESTIVALS` is `winter_festival` only, `gatheringDay`), `festival/Gifts.java:141-158`, `festival/Fireworks.java:77-80`; csfullcycle base, csfestival | scarves: yes (csclient proves the layer, not the picture) |
| P4 | After 6 January he takes it all down, and again next year | `SeasonsConfig.java:44` (decorUntil 01-06), `decor/DecorWork.java:48-50` (the take-down); csfullcycle base (on 10 Jan the world equals the first snapshot) | |
| P5 | Requires NeoForge 1.21.1, MineColonies 1.1.1420 or newer in 1.1, Structurize and Domum Ornamentum; Serene Seasons optional; server and every client | `resources/META-INF/neoforge.mods.toml:15-51` (every dependency `side="BOTH"`, Serene Seasons not listed); csboot, `tools/packboot.sh` (item 25) | the client side: yes |
| P6 | A new job, the Decorator's Workshop, levels 1 to 5 | `colony/BuildingDecorator.java:38,130`, `colony/DecoratorModules.java:25-28`; csdecorator, cspaste | |
| P7 | All year (with "Prepare all year" on) a stock of the decorations he makes; supply order: his own storage, then finished pieces from the warehouse, then materials; with materials off he needs no stock | `ai/EntityAIWorkDecorator.java:248-258` (free: nothing to prepare; "Prepare all year" off: only in a window), `colony/DecoratorModules.java:37` (the setting on by default), `decor/Supply.java:44-52` (`plan`), `ai/EntityAIWorkDecorator.java:311-377` (`prepare`); csstorage (a warehouse and a real courier), `restart.sh csstorage` | |
| P8 | Materials such as leaves, string, dye, paper, torches and glowstone dust | `resources/data/colony_seasons/decorator_kits/*.json` (glass panes and gold nuggets too, hence "such as") | |
| P9 | `decorator.needsMaterials = false` makes his work free | `SeasonsConfig.java:164-167`, `ai/EntityAIWorkDecorator.java:248-252`; csdecorator mode free, csfullcycle hostile-free | |
| P10 | Town hall first | `decor/DecorWork.java:181` (`nextToDecorate`: the town hall, then the square, then the nearest building); csfullcycle base | |
| P11 | Level 1: a wreath above the main door; higher levels garland beside it and lights above it; the grand tree with the large star at 5 | `decor/DecorPlanner.java:20-27,84-93`, `decor/PieceLoader.java:107-114`; csdecorgate styles and setup, cspaste | yes: a level 1 and a level 5 hut (item 1) |
| P12 | A building with no outside door gets one wreath on the wall nearest its hut block | `decor/DecorPlanner.java:160` (`planDoorless`), `decor/DoorFinder.java:33` (`MIN_OUTSIDE_SKY`); csanswers | |
| P13 | Only into empty air, inside his claim, every block written down; takes down exactly those, only if unchanged; your own builds left alone | `decor/DecorPlacer.java:44-60` (the cell rule and the removal rule), `decor/DecorLedger.java`; csfullcycle every mode (I1 to I4), csdecorgate lifecycle, hostile-items H5 | |
| P14 | The trunk is the mod's own block, so a Forester never cuts the tree | `block/FestivalTrunkBlock.java`, `decor/PieceLoader.java:42-44`; csforester, `tools/build_pack.py` (no log block in a piece) | |
| P15 | His stock in the Workshop's racks, 2 at level 1 and 12 in the hut at level 5 | `tools/decorator_hut.py:18-20,228,240-255` (2, 3, 6, 9 and 12 in the whole hut; the attic room holds 2, 4 and 6 of them from level 3); `tools/build_pack.py` (the room's rules), cspaste | yes: renders only so far (`docs/renders/s10e/`) |
| P16 | Upgrade Storage at level 5: one emerald block a step, three steps, every rack one row larger, like the Warehouse's | `colony/BuildingDecorator.java:60-66,228-251`, `network/UpgradeStoragePayload.java:88-130`; csstorage (c, e2), `restart.sh csstorage` | yes: the tab after a click (S10h fixed its redraw, which the fourth in-game test saw stale) |
| P17 | He dresses the storeroom: packed outside the festival; from the first day of the window until his take-down is done, nearly empty boxes and garland, a wreath and fairy lights | `decor/StoreroomWork.java:94-104` (`target`), `decor/StoreroomSpots.java:21-25` (the pieces); csstoreroom, csspots, `restart.sh csstoreroom` | yes: only renders of the rig's rooms so far (`docs/renders/s10g/*-s10g2-*.png`) |
| P18 | `decorator.storeroomDressing = false` leaves the room bare | `SeasonsConfig.java:177-181`, `decor/DecorEvents.java:318-327` (`roomGoes`: the switch off removes the room); csstoreroom | |
| P19 | Place a marker on the square, inside the colony, and the tree goes there | `block/FestivalMarkerBlock.java:67-81,94-103`, `decor/DecorEvents.java:147-163`; csdecorgate square | |
| P20 | Only a marker a player places by hand counts | `block/FestivalMarkerBlock.java:78` (`placer instanceof Player`); csanswers (a paste through Structurize's own placement call) | |
| P21 | When the tree is finished you are told; a right-click lights the ornaments, the star and the lights on the buildings; after two game days the Decorator lights it | `festival/TreeLighting.java:24-31,40,108,121,165`; csfestival, csfullcycle base | yes: the lights at night |
| P22 | From Workshop level 2 a gift pile; from 1 December one gift a day for each player of the colony | `decor/PieceLoader.java:60,289` (no pile below level 2), `festival/Gifts.java:141-158`; csfullcycle base, csdecorgate square, csfestival (a visitor's click is refused by MineColonies' protection) | |
| P23 | 24 to 26 December: colonists walk to the tree in the afternoon, not when it rains; the Decorator hands them gifts; a better gift | `festival/Gathering.java:40-60,275` (no gathering in rain), `ai/EntityAIWorkDecorator.java:785` (he hosts only when it does not rain), `festival/Gifts.java:50-52,92-102`, `SeasonsConfig.java:45-46,62-63`; csfullcycle base, csfestival | yes: the colonists walking (two probe runs saw slow paths, S9) |
| P24 | 31 December and 1 January: fireworks at dusk; a countdown and a big show at midnight if the server runs through it | `festival/Fireworks.java:52-80,404`, `SeasonsConfig.java:198-200`; csfullcycle base, csfestival newyear | yes: the rockets in the sky |
| P25 | The gifts come from loot tables | `festival/Gifts.java:50-52`, `resources/data/colony_seasons/loot_table/gifts/` | |
| P26 | A winter scarf in five colours, a wool hat without headgear of its own, the striped scarf 1 Dec to 6 Jan, client only, nothing saved | `client/ClothesRules.java` (`COLOURS`, `outfit`), `client/ClothesLayer.java`; `tools/clothescheck.sh`, csclient | yes |
| P27 | 48 game days, twelve a season, changeable; a new world starts in the season of the real date; follows Serene Seasons | `SeasonsConfig.java:31-34,132-140`, `calendar/SeasonCalendar.java:25-27`, `calendar/CalendarData.java:36-48`, `calendar/SereneBridge.java`; csconfig, cscalendar | the real Serene Seasons: yes (item 26) |
| P28 | Three effect switches, all off; with them off the mod changes looks, sounds, the Decorator's materials, the gifts and the gathering; by default working colonists (not the guards) leave work for the gathering hours of one game day on each real day of 24 to 26 December, `events.whoComes = off_duty` keeps them at work; on New Year evenings the Decorator stays up for the dusk show | `SeasonsConfig.java:64,68-77,206-228`; `festival/Gathering.java:252-263` (`everyoneNow`), `:394-425` (`bringWorker`: never a guard, the Decorator or a baby, not in rain), `mixin/CitizenAIFestivalMixin.java:46-50` (the working colonist made idle); `festival/Fireworks.java:372-401` (`keepUp`), `:516-545` (rockets from his stock); cseffects off and on, csfullcycle effects, csfestival default and nomixin | |
| P29 | The commands table; operators for all but `status` | `command/SeasonsCommands.java:29,48-60`, `command/DecorCommands.java:32-42`; cscalendar, csdecorgate lifecycle | |
| P30 | The config switches table, keys and defaults | `SeasonsConfig.java:54-81` (defaults), `:164-234` (keys); csconfig defaults | |
| P31 | The Workshop window's switches: Prepare all year, Decorate, wreaths, garlands, lights, the tree | `colony/DecoratorModules.java:34-43`, `colony/BuildingDecorator.java:48-58`; cseffects (the planner honours them) | |
| P32 | Only two festivals, others later (no dates, no names promised) | `SeasonsConfig.java:40` (`FESTIVAL_IDS`) | |
| P33 | Without a Workshop: no tree, gifts or fireworks; the colonists dress and gather at the marker or the town hall on the Winter Festival days | `festival/Gathering.java:157-165` (`site`), `:140-151` (`gatheringDay`), `festival/Fireworks.java:501-521` (no show without a staffed hut); csfullcycle nodecorator and nomarker | |
| P34 | Streets are not decorated | no road code; design 3.4 | |
| P35 | The happiness bonus is in the citizen window's happiness number but not in its list of reasons; the hut window shows it | `effects/HappinessEffect.java:18-28`; cseffects on (the client's total equals the server's; our id not in the window's list) | |
| P36 | MineColonies' own crops are not slowed in winter | `effects/CropEffect.java:14,54` (`CropGrowEvent.Pre` only); cseffects on ("[V] MineColonies' own crop block grows, and does not fire CropGrowEvent.Pre") | |
| P37 | English only | `resources/assets/colony_seasons/lang/` holds only `en_us.json` | |
| P38 | Uninstalling: `decor remove` first; the trunk goes with the mod, the spruce leaves stay, at level 5 also the planter's stone bricks, podzol and lanterns | `tools/tree_pieces.py:141-155`; hostile-world H18 | |
| P39 | Source and changelog on GitHub; bug reports there or in the comments | `gh repo view` on 22 Nov 2026: public (made public at the release on the owner's word); see item 29 | |
| P40 | The Discord and Patreon links; one chat line the first time a player joins a world, once in that world; `links.showWelcome = false` turns it off | `links/Welcome.java:29-39,66-77,90-99`, `SeasonsConfig.java:230-235`; `tools/check_links.py`, csanswers | |

Found by S11b in the code and not changed (only the page texts and one tooltip were in scope; for the owner):

- `SeasonsClientConfig.showParticles` is in the client config with a comment that promises snow over the tree, the
  glow of lit decorations and firework sparks, but nothing reads it and the mod has no particles of its own (the
  only ones are the hearts the server sends at the gathering, `festival/Gifts.java:208`). The pages now say it does
  nothing yet. Fixed (S10i, the owner's answer of 21 Nov 2026): the key is gone (`SeasonsClientConfig.java`); a
  client file that still has it loads and NeoForge drops the key, keeping `showClothes` (csconfig seeded, with the
  file 0.1.0-beta.1 wrote); csconfig defaults checks the written file's keys against a hand-written list. The README
  sentence and the player changelog's limit are gone.
- The hut window never says that the tree does not fit at the marker: `DecorLedger.squareProblem` is set
  (`decor/DecorWork.java:645`) and csdecorgate checks that it is "noted for the hut window", but
  `colony/DecoratorInfoModule.java` never reads it, and the status line then reads "Decorated Festival square: 0
  placed, 0 still open." What the player gets is the managers' chat line once a game day; the pages now say that.
  Fixed (S10i, the owner's answer of 21 Nov 2026): `colony/DecoratorInfoModule.java` (`read`) sends the note with the
  page while a decoration window is open, `client/DecoratorInfoWindow.java` (`lines`) shows one red line under
  "Now:" (`com.colony_seasons.gui.info.square_no_room`), and a colony tick sends the page again when the note
  changes; csdecorgate square reads the module's own packet back (the line on, off outside the window, gone once the
  tree fits). The chat line stays. README and the player changelog say both again.
- The tree switch (found by the S11b review): only `nextToDecorate` reads it (`decor/DecorWork.java:192`). With the
  tree off, a tree he is still building stays half built (`nextToTend`, `:450-471`, takes only a group whose
  `decoratedDay` is set, which a tree gets when it is finished or found no room, `:644,698`); a finished or no-room
  tree is still tended every day; after a Workshop upgrade tending takes the old tree down (`:607-613`), the emptied
  group is dropped (`decor/DecorPlacer.java:471`), and no new tree goes up. The README now says exactly that.
- `resources/META-INF/neoforge.mods.toml:4,12` (`issueTrackerURL`, `displayURL`) point at the GitHub repository, which
  is private (21 Nov 2026), so the mod list's links are dead for players until it is public. Left as they are: whether
  the repository goes public is the owner's call (item 29).

## The S11b review round (21 Nov 2026)

An independent checker read the pages against the code after the S11b pass. What it found and what changed (only page
text; no code):

- Effects off: "with them all off the mod changes only looks, sounds, the Decorator's materials and the gifts" left out
  the gathering, which by default takes the working colonists (not the guards) off work for the gathering hours of one
  game day on each real day of 24 to 26 December (`SeasonsConfig.java:64`, `festival/Gathering.java:252-263,394-425`,
  `mixin/CitizenAIFestivalMixin.java:46-50`), and the Decorator's late New Year evening (`festival/Fireworks.java:372`).
  CurseForge page and README now name both and `events.whoComes = off_duty` (P28).
- The GitHub link: the repository is private, so the CurseForge page's "Source and changelog on GitHub ... Bug reports
  there or in the comments here" became "Bug reports in the comments here, with your `logs/latest.log`." (P39). When
  the repository is public, the sentence can come back word for word: "Source and changelog on GitHub:
  https://github.com/Lovkar-Squid/colony-seasons. Bug reports there or in the comments here, with your
  `logs/latest.log`."
- README "he never builds round a player or an animal" became "he never puts a block into a cell where a player or an
  animal stands; that cell waits for a later visit" (`decor/DecorPlacer.java:178-207`, the "entity" refusal; `place`,
  `:342-347`, still fills the other cells; `DecorWork.evict` moves colonists only).
- README "Switching the tree off stops a new tree from going up" became the rule as the code has it (see the tree switch
  above).
- "They gather at the tree on the festival days" (CurseForge page twice, README twice) became "on the Winter Festival
  days (24 to 26 December)": the gathering is for `winter_festival` only (`festival/Gathering.java:82,140-151`), and
  "not when it rains" was added where the gathering is described (`:275`, `ai/EntityAIWorkDecorator.java:785`) (P3,
  P23, P33).
- README "the gift pile and the firework crate go up last, once the tree's own trunk, leaves, ornaments and star are in
  place" became: with materials on, the parts that cost nothing (the pile, the crate, and at level 5 the large star and
  the tinsel leaves) wait until every part of the tree and its planter that needs materials is up; with decorations
  free everything goes up together, trunk first, then bottom up (`decor/DecorWork.java:403-421,670-689`,
  `decor/PieceLoader.java:285-287,323-338`). Item 30 below is the record.
- The welcome marker "shared with Lovkar's other mods" (README, changelog) is not proven: only `links/Welcome.java` uses
  `lovkar_mods` / `welcome_shown`. The README now says the marker has a neutral name so the author's later mods can
  share it; the changelog drops the clause.
- Minor: the racks are "2 at level 1 and 12 in the hut at level 5" (CurseForge page; the README now says which racks
  stand in the storage room, `tools/decorator_hut.py:228,239-255`) (P15); the happiness bonus is in the citizen
  window's number but not in its list of reasons (CurseForge page, P35); the wool hat keeps the plain colour while the
  scarf is striped (`client/ClothesRules.java:93-95`); the stock is of the six decorations he makes, kept all year only
  with materials needed and "Prepare all year" on (README, CurseForge page and changelog;
  `ai/EntityAIWorkDecorator.java:248-258`, `decorator_kits/*.json`) (P7); the later festivals are only "later", with no
  names and no "each before its real date" (CurseForge page, README) (P32); the changelog's gift a day runs from 1
  December to 6 January and is no longer listed under the festival days (`festival/Gifts.java:104-108`). The checker's
  report was cut off inside this last item, so its exact wording was not seen; the fix follows the code.
- `release/curseforge-page-coming.md` (the text while no file is up, replaced by the description at the upload) still
  says "on the festival days" and "each before its real date"; it was left as it is, as before. If it is live on
  CurseForge until the upload, the owner may want those two phrases changed there too.

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
  line once a game day (see above). S10i: the Calendar page says it again, next to the chat line.
- Lighting: the strings of lights "on the buildings" (`festival/TreeLighting.java:26-28`).
- Gifts: from Workshop level 2 (`decor/PieceLoader.java:60`), `events.giftsPerPlayerPerDay`, the colony's
  protection, all three gift tables.
- Clothes: `showParticles` does nothing yet (see above). S10i: the key is gone, and the sentence with it.
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
   Changed (S10i): the hut window shows the note again (the owner's answer of 21 Nov 2026), as a line on the Calendar
   page while a decoration window is open, and the chat line stays; csdecorgate square checks the page's packet. The
   README says both. The CurseForge page has no sentence about the room (S11b's "and the CurseForge page" above was
   wrong) and stays as approved.
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
   S11b review: `gh repo view` says `isPrivate: true` (21 Nov 2026). The GitHub sentence is off the CurseForge page
   (the sentence to put back is in "The S11b review round"); `neoforge.mods.toml:4,12` still name the repository and
   are dead links in the mod list until it is public. Open for the owner: make the repository public before the
   upload, or have the two URLs changed.

## Added by S9

30. The gift pile and the firework crate come with the finished tree: they (and at level 5 the large star and the
    tinsel leaves) go up last, once every log, leaf, ornament and the star the Decorator pays for is in place.
   Changed: README (the Decorator's level table, the sentence below it). Proof: hostile-world H8 (no stock: nothing
   placed; logs only: the trunk and nothing free); csfullcycle emptywarehouse (nothing ever placed).
   Changed (S10c): README "once the tree's own trunk, leaves, ornaments and star are in place"; a paid cell the cell
   rule refuses for good (a lantern whose planter brick cell holds grass) no longer holds the free ones back.
   Proof: csdecorgate square ("the grand tree with short grass on a planter cell").
   Changed (S11b review): "go up last" held only with materials on at levels 1 to 4. README now: with
   `decorator.needsMaterials` on, the parts that cost nothing (the gift pile, the firework crate, and at level 5 the
   large star and the tinsel leaves) wait until every part of the tree and its planter that needs materials is up; with
   decorations free everything goes up together, trunk first, then bottom up (`decor/DecorWork.java:403-421`,
   `decor/PieceLoader.java:285-287`).
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

## Added by Q9 (0.2.0-beta.1: the Town Square, the stalls and the wand)

State: Q1 to Q7b, Q10, Q11 and Q12 merged (main 1edbbe3). The new CurseForge paragraphs are in
`release/curseforge-0.2.0-changes.md`, marked NEW or CHANGED for the owner's approval; README.md and
`CHANGELOG-0.2.0-beta.1.md` carry the same claims. "Proof" names the probe and mode (BUILDING.md lists each one). The
claims that only an in-game look can prove are marked "Look" (Q8, the lead's in-game test).

| # | Claim | Proof | Look |
| --- | --- | --- | --- |
| T1 | The Town Square is a hut of five levels, 21 x 21 at every level, built by the Builder; the plaza, benches, lamp posts, planters, stall pads and bandstand come by level | `cssquare` (five levels in the tag file, pasted in eight orientations), `cspaste`, `cssquarelife builder` (a real UPGRADE 1 to 2 by MineColonies' Builder); `tools/build_pack.py` | yes: the plaza |
| T2 | One Decorator is assigned to the square, in its window or by auto-hire, and stays employed at his Workshop | `cssquare` (assign by `assignCitizen` and by a hire message, a non-Decorator refused, auto-hire, a dead or re-jobbed one dropped), `restart.sh cssquare` | |
| T3 | The colony's main square is the built one of the highest level (a tie kept by the one that has it); a second square is told once; a removed square hands back to the marker | `cssquarelife`, `csfullcycle hostile-square` (two squares, a square in another colony's claim) | |
| T4 | The tree on the square is the Winter Festival's tree, sized by the lower of the two levels, equal cell for cell to the marker's, with a foot in the centre cell | `csdecorgate square`, `cspaste`, `cssquarelife` (level 3 square, the Decorator's own AI) | yes: the tree on the plaza |
| T5 | The order (Follow the calendar, Always Winter Festival, Bare); a changed order takes the old set down first; an unknown stored value reads as Follow with one log line | `csdecorgate square`, `cssquare` | |
| T6 | The events never depend on the order: the gift pile at the `gift` cell, the crate at `launch`, the gathering, the lighting and the shows follow the calendar, Bare included | `csfestival square`, `csfullcycle square-building` (I5 to I8 after every game day) | yes: the gathering |
| T7 | The gathering stands on the square's `gather` spots and its hut is the leisure site in the gathering hours | `csfestival square` | yes |
| T8 | The square's info page and the Workshop's one line show the facts the page lists; nothing on them writes a ledger or sends chat | `cssquare`, `cssquarelife`, `csdecorgate`, `csclient` (the pages' views on a dedicated server), `tools/check_packets.py`, `tools/check_windows.py` | yes: the layout |
| T9 | A work order on the square takes the decorations down at once, by the exact-state rule | `cssquarelife builder`, `csfullcycle builder-over-tree` | |
| T10 | Between the festivals the in-game season's touch stands in each planter; they come down for the Winter Festival's window, under Bare and for a work order; a 0.1.0 world skips their group with one warning | `cssquarelife`, `restart.sh cssquarelife`, `csfullcycle touches` | yes: `docs/renders/q12/` are renders, not screenshots |
| T11 | Moving from the marker: nothing converts by itself; with a square at level 1 the marker's tree comes down, the items go to the Workshop, the managers get one line, the Decorator builds the set on the square; the marker stays as the fallback; a colony never has two centrepieces | `csfullcycle migrate-marker`, `cssquarelife` (marker to square and back, a restart in the middle) | |
| T12 | The decoration record is format 3; files of 0.1.0 load unchanged; 0.1.0 reads a 0.2.0 file | `cssquarelife` (files with no version, version 1, 2 and 3), `tools/checks.sh` (the 0.1.0 reader) | |
| T13 | `decor list <colony>` gives a `Centrepiece:` line first (the Town Square and its tree cell, the marker, or none), then the marked spots and the street lights | `cssquarelife`, `cswand`; `resources/assets/colony_seasons/lang/en_us.json` (`command.decor.list.square.*`) | |
| T14 | With Trade Post for MineColonies and `compat.tradePostBridge` on, a level 4 or 5 square has 2 or 4 stalls while a festival set stands; colonists buy festival goods in the gathering hours; each sale pays its price into Trade Post's balance; at most one Trade Coin per stall per colony day | `cstradepost with`, `csfullcycle trade-post`, `MarketMathTest`, `tools/check_market.py` | yes: the stalls |
| T15 | The mod keeps no money of its own: a payment is made on the server thread, read back, and written to the record first, so a restart pays nothing twice; on any failure the stalls stop until the next start with one warning | `cstradepost with`, `restart.sh cstradepost` (save, mid, and the jar taken out), `csfullcycle hostile-square` (H26, the throwing `markDirty`) | |
| T16 | Without Trade Post, or with the switch off, nothing changes: no Trade Post class is looked at, no stall, no money line, no log line; no compile dependency | `cstradepost without`, `tools/check_nodeps.py` | |
| T17 | The wand: right-click in the air picks the mode (snowman, snowman with a top hat, lantern post, garland between two posts, seasonal planter), right-click a block marks, sneak and right-click removes and takes the pieces down at once | `cswand` (all five types, every refusal with its key, removal) | yes: the item in hand |
| T18 | A mark needs the colony's claim, the right to manage huts, empty air, ground under a snowman or post (a dirt path counts) or a full block under a planter; it is refused inside any building's box and within 5 blocks of the festival marker; at most 64 marks | `cswand`, `csfullcycle hostile-square` (H25, H28), `csfullcycle wand` (a hut over a mark) | |
| T19 | In every window the Decorator dresses the marks after the town hall and the square, on one slot of the day's budget; the pieces are free, have collision and are not solid; after the window they come down by the ledger rule and the marks stay | `cswand`, `restart.sh cswand`, `csfullcycle wand` | yes: a street |
| T20 | `decorator.streetLights` (off by default): thin lights on every third fence or wall post beside a path block, at most 48, no collision, one group, never with the hut's lights kind off | `cswand lights`, `csconfig defaults`, `csconfig seeded` | yes: the lights at night |
| T21 | The holder of a wand sees the colony's marks as outlines (client side; network protocol 3) | `tools/check_packets.py`, `csclient` | yes |
| T22 | Config: `decorator.streetLights` false, `compat.tradePostBridge` true | `csconfig defaults`, `csconfig seeded`; `SeasonsConfig.java` | |
| T23 | The version is 0.2.0-beta.1 | `resources/META-INF/neoforge.mods.toml`; `tools/build.sh` builds `colony_seasons-0.2.0-beta.1.jar`; `csboot` | |
| T24 | Without a Decorator's Workshop the colonists gather at a built Town Square, else the marker, else the town hall (CHANGED sentence of "What it does not do") | code only: `Gathering.site` takes the main square's stored tree cell first, and `Squares.refresh` stores it on the square's construction event with no Decorator check; `csfestival square` proves the square's gathering with a Decorator, no probe runs one without | yes: a colony with a square and no Workshop |
| T25 | Taken out without `decor remove`, the stalls' posts, slabs and wool and the planters' leaves or pumpkin stay (CHANGED removal paragraph); `decor remove` takes them down with every other group | code: `MarketStalls` (stripped spruce posts, slabs, wool), `tools/square_hut.py` `TOUCHES` (vanilla blocks), `DecorPlacer.removeColony` (every group of the colony) | |

Replaces item P34 ("Streets are not decorated"): since 0.2.0 streets are decorated where the player marks them with the
wand (T17 to T20). The Decorator still never picks a street spot himself. The CurseForge sentence is the one CHANGED entry
in `release/curseforge-0.2.0-changes.md`; the README's "What it does not do" carries the same change.
