# Colony Seasons: design for 0.1.0

"Colony Seasons – Seasons and Festivals for MineColonies". NeoForge 1.21.1, Java 21, GPL-3.0-or-later, plain javac build, no dependency on Lovkar's other mods. Mod id `colony_seasons`, package `me.lovkar.seasons`, command root `/colonyseasons`.

Written 19 Nov 2026 from the owner's 8 decisions (`D:\Claude\colony-seasons-plan-2026-11-19.md`) and four code-reading reports in `research/` (`a-own-addons.md`, `b1-citizen-look.md`, `b2-colony-apis.md`, `c-serene-seasons.md`). Nothing is built yet.

How to read the marks:

- **[B] binding**: an owner decision or a safety rule. A builder does not change it; if it cannot be met, stop and report.
- **[A] advisory**: the intended shape. A builder may do it differently for a stated reason, and writes the reason in the commit.
- **[V] verify first**: rests on something the research could not prove from bytecode. The chunk that needs it proves it on the rig before building on it, and falls back as written if it fails.
- **[O] owner choice**: look and feel. Section 12 lists them with their state.

## 1. What 0.1.0 is

The colony follows a calendar. Seasons run in the game; festivals come on real dates. A new worker, the Decorator, puts decorations on the colony's buildings and a decorated tree on the square before the Winter Festival, and takes them all down afterwards. Colonists dress for winter, gather at the tree, the player gets gifts and New Year fireworks. Effects on the game exist but are off.

Owner decisions, all [B]:

1. Festivals on real dates; seasons in the game on a calendar with a configurable length; follow Serene Seasons when it is installed.
2. Four kinds of change: decorations, clothes and behaviour, festival events, effects on the game.
3. Effects on the game are OFF by default.
4. Decorations come from the Decorator, a new hut and job, using warehouse materials; a higher hut level gives more and better decorations.
5. Neutral festival names: Winter Festival, New Year, Night of Lanterns, Spring Bloom, Harvest Fair. No religion or country in names, textures or text.
6. 0.1.0 holds the calendar, seasons, the Decorator, Winter Festival and New Year. The other festivals come later, each before its real date.

## 2. Calendar

### 2.1 Real dates (festivals)

- [B] Festival windows are tested against the server's local date. One class, `DateSource`, is the only place that reads the clock, so tests and the force command can replace it.
- [A] Config `calendar.timeZone` (default `system`) for hosted servers whose clock is in another zone.
- [B] Default windows (the owner's choice of 19 Nov 2026; all dates configurable):

| Window | Default dates | What happens |
|---|---|---|
| Winter season of decorations | 1 Dec to 6 Jan | decorations go up from the first day and stay; the tree stands; one small gift a day |
| Winter Festival days | 24 to 26 Dec | the gathering at the tree, the better gift, colonists get gifts |
| New Year | 31 Dec to 1 Jan | fireworks at dusk, the midnight show |
| Take-down | from 7 Jan | the Decorator removes everything he placed |

- [B] `/colonyseasons festival force <id>` (permission 2) starts a festival outside its dates, for tests, videos and servers that want it; `festival clear` ends it. The forced state is saved with the world.
- [A] Later option, not in 0.1.0: festivals tied to the in-game calendar instead of real dates.

### 2.2 Seasons (in the game)

- [B] One calendar per world, not per colony, so neighbouring colonies agree and it matches how Serene Seasons works.
- [A] No ticking counter. `day of year = (level.getDayTime() / 24000 + offset) mod yearLength`. Sleeping and `/time` then behave naturally. `offset` lives in a `SavedData` (`colony_seasons_calendar`); `/colonyseasons season set <season>` changes it.
- [B] Default year length: 48 game days (12 a season, about 4 hours of play; the owner's choice). Config `calendar.yearLengthDays`, 4 to 960, a multiple of 4.
- [A] `calendar.startSeason = auto` (default): a new world starts in the season of the real date (northern hemisphere), so a colony founded in December starts in winter. Other values: `spring`, `summer`, `autumn`, `winter`.
- [B] Serene Seasons bridge, reflection only, no compile dependency: `sereneseasons.api.season.SeasonHelper.getSeasonState(Level)`, then `getSeason()`, `getDay()`, `getCycleDuration() / getDayDuration()`. Polled every 20 ticks (its change event is on GlitchCore's bus, which reflection cannot subscribe to cleanly). Any exception or a dimension that is not seasonal falls back to our calendar for good in that session, with one log line. Config `calendar.useSereneSeasons` (default true). Only Serene Seasons in 0.1.0.
- What a season shows while effects are off: colonists' clothes (section 7), the season and day in the Decorator's hut window and in `/colonyseasons status`, one chat line to the colony's players when the season turns, a few seasonal greeting lines. No world change: no snow, no leaf colours, nothing placed. [A] Seasonal street decorations come with each later festival version.
- [B] The server sends the state to clients (`SeasonStatePayload`: season, day of year, year length, active festival ids) at login and on every change. Clients never decide dates themselves, so every player sees the same thing and the force command works.

## 3. Decorations

### 3.1 How they are represented: both ways

- **Thin blocks of our own** for everything that goes on a building: wreath, garland, lantern string, fairy lights. They have no collision, do not block paths or doors, and break instantly.
- **Pieces** (Structurize blueprints in our own pack) for the bigger things on the square: the tree (the village spruce in two sizes, the grand tree for hut level 5), with its ornaments, star, gift pile and firework crate. They are placed by our own code block by block, not by the Builder.

Why our own blocks on buildings: removal is exact (a block is taken away only where the ledger says we put it and our block is still there), nothing of the player's can be confused with it, and every colony style gets the same decorations without touching its blueprints.

Blocks of 0.1.0 [A], look per section 12:

| Block | Where | Notes |
|---|---|---|
| `wreath` | on a wall face | 4 facings |
| `garland` | on a wall face, tiles sideways | 4 facings |
| `lantern_string` | on a wall face, tiles sideways | warm paper lanterns; gives light when lit |
| `fairy_lights` | on a wall face, tiles sideways | small coloured bulbs; gives light when lit. The bulbs in the picture are too small to read from a distance: make them larger |
| `ornament_leaves` | part of the tree | spruce leaves with baubles, `lit` property. The baubles in the picture are specks from a distance: make them larger |
| `tinsel_leaves` | part of the large tree | spruce leaves with a gold garland swag |
| `tree_star` | on top of the tree | `lit` property |
| `large_star` | on top of the large tree | two blocks tall; merge the model's many small boxes into few |
| `gift_pile` | at the foot of the tree | block entity: stock, who took a gift today |
| `firework_crate` | by the tree, hut level 3+ | launch point for the New Year show |
| `festival_marker` | placed by the PLAYER | marks the square; block entity |
| `blockhutdecorator` | the hut | |

Every decoration block also has an item and a crafting-table recipe, so players can decorate by hand. A block placed by a player is not in the ledger and the Decorator never touches it.

### 3.2 Safety rules for placing

- [B] Place only where the world block `isAir()`. Not "replaceable": grass, flowers, snow and water stay.
- [B] Place only in loaded chunks, inside this colony's claim, never inside another colony.
- [B] Nothing with collision is ever placed automatically, except at a `festival_marker` the player put down (0.2.0: or inside the footprint of a Town Square the player built, or at a spot the player marked with the Decorator's wand; the owner's answers of 20 Nov 2026).
- [B] Never break, replace or move a block that the ledger does not list.
- [B] Every placed block is written to the ledger before the next one is placed: position, exact block state, festival, the building or marker it belongs to.
- [B] Skip a building while `isPendingConstruction()` or while it has an open work order, and take our decorations off it first (the Builder's CLEAR_NON_SOLIDS stage would set them to air during an upgrade or repair).

### 3.3 The ledger

- [B] `DecorLedger extends SavedData`, per level, keyed by colony id. It is NOT stored in the hut: hut module data is lost when the hut is destroyed, and the decorations must not become orphans.
- [B] Removal rule: remove a position only if its chunk is loaded and the block state there equals the recorded state exactly. Then set air with no drops and give the item back to the worker or the hut. If the state differs (a player changed it, the Builder cleared it), leave the world alone and forget the entry.
- [B] `/colonyseasons decor remove <colony>` (permission 2) removes everything the ledger lists for a colony at once, by the same rule. `decor list` counts entries.
- [B] No hut any more, or no Decorator, when the window ends: the server removes the decorations itself on colony ticks, chunk by chunk as they load.
- [A] A building is removed (`BuildingRemovedModEvent`, MineColonies' own event bus): its decorations go at once, server side.
- [A] Decoration blocks tell the ledger when they are broken (`onRemove`), as Colonies at War's wall blocks tell `WallRegister`.
- Uninstalling the mod: our blocks vanish with it; the vanilla logs and leaves of the tree stay. The README tells admins to run `decor remove` first.

### 3.4 Where decorations go on a building

Rule-based, so it works on every style [A], [V] on real huts of at least six style packs in the placing probe:

1. Scan the building's box (`IBuilding.getCorners()`) for the lower halves of doors (`BlockTags.DOORS`). MineColonies keeps no entrance list.
2. The outside of a door is the side with more sky light one block out; on a tie, the side farther from the hut block.
3. Wreath: the air cell in front of the wall block above the door (door + 2 up, one out), if that wall block has a sturdy face.
4. Garland: the same row, left and right of the wreath, cell by cell while there is air in front of a sturdy face.
5. Lights: the row above the garland, same rule. A building gets either the lantern string or the fairy lights, picked from its position, so streets are mixed but a building keeps its kind.
6. No slot found: the building gets nothing. That is fine.

Amounts by hut level [A]:

| Decorator's hut | On each building | On the square |
|---|---|---|
| 1 | a wreath on the main door | village spruce, small |
| 2 | + garland, 2 each side | village spruce, small; gift pile |
| 3 | + lights, 2 each side; garland 3 | village spruce, full size; firework crate |
| 4 | garland and lights 4 each side | village spruce, full size, more ornaments |
| 5 | up to 6 each side, second door too | the grand tiered tree with the large star |

Roads are not decorated in 0.1.0: MineColonies has no road graph (only the `#minecolonies:pathblocks` tag), and solid things on streets can block colonists. 0.2.0 (Q11): the player marks spots with the Decorator's wand (snowmen, lantern posts, a garland across a street, planters), and the server switch `decorator.streetLights` (off by default) lets the Decorator hang thin lights without collision on fences and walls beside path blocks.

### 3.5 The square

- [B] The player chooses the square by placing a `festival_marker`. Without a marker there is no tree; buildings are still decorated. One marker per colony in 0.1.0 [A].
- [A] The piece is placed relative to the marker and its facing; the marker itself is never replaced.
- [A] If cells of the piece are blocked: place the rest when at most 10 % are blocked and no trunk cell is; otherwise place nothing and say "not enough room at the festival marker" in the hut window and once a day to the colony's managers.
- [V] `StructurePacks.getBlueprintFuture(pack, path, registries)` (the plain call blocks), `setRotationMirror`, `getBlockInfoAsList()`, world position = anchor - `getPrimaryBlockOffset()` + `info.getPos()`. Colonies at War's `WallOrders.paste` is the working example of loading and rotating.

## 4. The Decorator

- Hut `blockhutdecorator`, building `colony_seasons:decorator`, levels 1 to 5, one worker. Template: Voyager's Observatory (non-crafter). AI base `AbstractEntityAIInteract`. He works by day, so no sleep mixin.
- [B] The mandatory `ModSoundEvents.CITIZEN_SOUND_EVENTS` entry (borrow another job's lines), own block-entity type listing the hut, `getRegistryName` override: the known gotchas of `a-own-addons.md` section 9.
- [A] Skills: Creativity first, Dexterity second. [A] No research gate in 0.1.0: the festival is tied to a date, so a young colony must be able to join in.
- [B] Nothing that must survive lives in AI fields (MineColonies resets the AI for sleep, rain and raids). Progress is the ledger; stock is items in the hut and the worker's pack; the queue position is building NBT.

His year:

| Phase | When | What he does |
|---|---|---|
| Preparing | all year, up to a cap | asks the warehouse for materials and assembles decorations at the hut, so stock is ready |
| Decorating | from the first day of the window | walks from building to building (town hall first, then nearest first), places what the level allows; `2 + 2 x level` buildings a game day |
| Tending | while the window lasts | once a game day re-places what went missing, restocks the gift pile |
| Hosting | festival days | stands at the tree in the festival hours, hands gifts to colonists (hearts and a thank-you line, no item moves), launches the fireworks |
| Taking down | after the window | visits every ledger group, removes by the rule in 3.3, stores the items in the hut for next year |
| Off season | otherwise | prepares stock, then idles like any colonist with nothing to do |

Materials [A]: a datapack file per decoration ("kit": ingredients by tag, output, count) in our own data folder, so an edit cannot break MineColonies' datapack. Examples: wreath x2 = 4 leaves + 1 string + 1 red dye; garland x4 = 4 leaves + 2 string; lantern string x4 = 2 string + 2 torches + 1 paper; fairy lights x4 = 2 string + 1 glass pane + 1 glowstone dust; wrapped gift x2 = 1 paper + 1 string + 1 dye; the tree asks for spruce logs and leaves; the show asks for firework rockets. The hut window lists what is missing. Config `decorator.needsMaterials` (default true; false makes decorating free).

Requests: `checkIfRequestForItemExistOrCreateAsync` and the tag variant; it returns true only once the item is in his pack.

## 5. Festivals: what the player does

[B] The owner chose all four activities on 19 Nov 2026 (lighting the tree, a gift a day, the gathering, fireworks with the midnight show) and the dates below.

**Winter season (1 Dec to 6 Jan)**
- Watch the colony fill with decorations over a few game days.
- Place the festival marker where the tree should stand.
- Light the tree: when the Decorator finishes it, the colony's players are told. A right-click on the tree lights every ornament, the star and the strings, with a chime and a few rockets. If nobody does it within two game days, the Decorator lights it.
- Take one gift a day from the gift pile (per player, per real day). A gift is an item that opens into a roll of a loot table (`colony_seasons:gifts/winter_daily`), so pack makers can change it.

**Winter Festival days (24 to 26 Dec)**
- The gathering: in the festival hours colonists come to the tree, the Decorator hands out gifts, a short song plays (our own synthesized sound).
- The better gift (`gifts/winter_festival`).

**New Year (31 Dec to 1 Jan)**
- Fireworks from the square at the first dusk of each real day with a player in the colony; smaller sparkle on the other dusks so the rockets last.
- [A] If the server runs through real midnight: a countdown on the action bar and the big show at 00:00.
- The gift of the day holds a few festival rockets.

Facts that shape the events:

- Colonists go to bed once `dayTime % 24000 > 10600` (about 16:36 on the game clock). [A] Festival hours are therefore the afternoon, ticks 8500 to 10500 (14:30 to 16:30). Fireworks at dusk are for the players.
- [A] Gathering without touching the AI: the marker's block entity is registered as a MineColonies leisure site (`addLeisureSite`), and every 5 seconds off-duty colonists (state IDLE) near the colony centre are sent walking to a free spot round the tree. [V] The leisure site needs an `IBlueprintDataProviderBE` at the position; if our block entity cannot be one, the walking alone stays.
- [B] Who comes (owner, 19 Nov 2026): once per real day every colonist comes, so work stops for the festival hours of that one game day; on the other game days of the festival only off-duty colonists come. Config `events.whoComes = everyone_once_a_day | off_duty | everyone` (default the first).
- [A] Bringing working colonists needs a small mixin at the head of `CitizenAI.calculateNextState`, the same place Voyager's night-shift mixin uses, written to fail soft (`require = 0`): if a MineColonies update moves the method, the mod still loads and only off-duty colonists come. [V] Both mods injecting at the same method head must be proven together on the rig.
- No colony event type in 0.1.0: MineColonies loads event types only under its own namespace and shows their names untranslated.

## 6. No Decorator, no hut

[A] The mod still does something in a colony without the hut: colonists dress for the season, off-duty colonists gather near the town hall on festival days, players get the season line. Decorations, the tree, gifts and fireworks need the Decorator.

## 7. Clothes

- [B] Drawn on the client only, as one extra render layer added to MineColonies' citizen renderer in `EntityRenderersEvent.AddLayers` (for `ModEntities.CITIZEN` and `VISITOR`; both use `RenderBipedCitizen`). No mixin, no item in any slot, nothing saved on the colonist. The layer reads `getParentModel()` every frame, because the renderer swaps models.
- Head-slot items are ruled out: guards re-equip armour, and it would be a real item in the inventory.
- [A] 0.1.0 wardrobe: a scarf in winter (five colours, picked by `getCivilianID()` so a colonist keeps his colour); a wool hat in winter on the models in an allow-list (data file in assets; many job models already have headgear); a striped festive scarf during the winter window.
- [B] MineColonies itself shows a Santa hat on every colonist with an empty head slot in real December (client config `holidayFeatures`, client clock). Our hat is not drawn when that hat shows; the scarf is. We do not turn their hat off.
- [B] We ship no textures into MineColonies' `nether` style folder (it is used for their late-October look).
- Client config `colony_seasons-client.toml`: `showClothes` (default true).
- [V] The scarf must sit right on the adult, female and child models; the chunk renders previews of at least eight job models before it is done.

## 8. Effects on the game (all OFF by default)

[B] Three server-config switches, all default false. With all three off the mod changes nothing but looks, sounds, the Decorator's use of materials and the gifts.

| Switch | When on |
|---|---|
| `effects.festivalHappiness` | on festival days every colonist gets a happiness bonus |
| `effects.winterCropSlowdown` | in in-game winter, crops inside colony claims skip half their growth steps (`CropGrowEvent.Pre`) |
| `effects.winterFoodNeed` | in in-game winter, colonists use about a quarter more food (a periodic extra `decreaseSaturation`) |

Known limits, to be stated in the README:

- [V] MineColonies keeps only its own fixed list of happiness ids across a world load and shows only those in the citizen window. Our modifier (`ExpirationBasedHappinessModifier`, our own id) is therefore re-applied by the server on colony ticks and counts, but is not listed in the citizen window. The hut window shows it instead. The chunk checks that the shown total still matches; reusing MineColonies' `quest` id is the fallback and needs the owner's yes, because it would read "Quest" in the window.
- [V] MineColonies' own crops grow through their own code and probably do not fire the growth event.

## 9. Config

Server (`colony_seasons-server.toml`, `ModConfigSpec` SERVER, getters that fall back to named default constants, as in Voyager):

| Section | Keys (defaults) |
|---|---|
| `calendar` | `yearLengthDays` (48), `startSeason` (auto), `useSereneSeasons` (true), `timeZone` (system) |
| `festivals` | per festival: `enabled` (true), `decorFrom`, `decorUntil`, `daysFrom`, `daysUntil` (month-day strings) |
| `decorator` | `needsMaterials` (true), `buildingsPerDayBase` (2), `buildingsPerDayPerLevel` (2), `maxPerSide` (6), `storeroomDressing` (true, S10g2), `streetLights` (false, 0.2.0 Q11) |
| `events` | `gatherFromTick` (8500), `gatherUntilTick` (10500), `whoComes` (everyone_once_a_day), `midnightShow` (true), `giftsPerPlayerPerDay` (1) |
| `effects` | `festivalHappiness` (false), `winterCropSlowdown` (false), `winterFoodNeed` (false), with their strengths |

Client (`colony_seasons-client.toml`): `showClothes` (true). (`showParticles` was taken out in S10i, the owner's answer of 21 Nov 2026: the mod has no particles of its own.)

[B] A config probe proves every default against a hand-written list and that a changed file is read back, as Voyager's `configcheck` does.

## 10. Code shape [A]

`me.lovkar.seasons`: `ColonySeasons` (the `@Mod` class, registries), `SeasonsConfig`, `SeasonsClientConfig`;
`.calendar` (`DateSource`, `Festival`, `FestivalCalendar`, `SeasonCalendar`, `SeasonState`, `CalendarData`, `SereneBridge`);
`.block` (decoration blocks, `BlockHutDecorator`, `SeasonsTileEntity`, `FestivalMarkerBlock`, `GiftPileBlock`, `FireworkCrateBlock`);
`.colony` (`BuildingDecorator`, `DecoratorModules`, `JobDecorator`); `.ai` (`EntityAIWorkDecorator`);
`.decor` (`DecorLedger`, `DecorPlanner`, `DoorFinder`, `DecorPlacer`, `PieceLoader`, `Kits`);
`.festival` (`FestivalDirector`, `Gathering`, `Gifts`, `TreeLighting`, `Fireworks`);
`.effects`; `.network` (`SeasonStatePayload`); `.command`; `.client` (`SeasonsClient`, `ClothesLayer`, models, `ClientSeasonState`); `.mixin` (`CitizenAIFestivalMixin`, soft-fail, for the gathering of everyone).

Blueprint pack: `blueprints/colony_seasons/colony_seasons/` in the jar, its own pack ("works with every colony style"), built with `voxel.py` and a `build_pack.py` copied from Voyager. Every block id is checked against the client jar and our own blockstates, because Structurize turns an unknown id into air without a word.

## 11. Not in 0.1.0

Spring Bloom, Harvest Fair, Night of Lanterns; seasonal street decorations; road decorations; festivals on the in-game calendar; players giving gifts to colonists; clothes for the player; other season mods; translations; a colony event entry in the town hall list.

## 12. Owner choices

All eight were answered by the owner on 19 Nov 2026 and are [B]. Pictures are in `design/` (`sheet-tree.png`, `sheet-hut.png`, `sheet-wreath.png`, `sheet-string.png`; sources of every variant in `design/src/`, how to re-render in `design/README.md`). They are daylight renders with the game's own textures and shading; no night view was made, so how the lights glow is not shown yet.

The `bunting` block drawn on hut B is not needed (hut A was picked).

| # | Choice | Answer (19 Nov 2026) |
|---|---|---|
| 1 | Tree for the square | A "Village spruce" at hut levels 1 to 4, growing into B "Grand tiered tree" at level 5 |
| 2 | Decorator's hut | A "Workshop with shopfront" (one look, five levels) |
| 3 | Wreath | A "Classic" |
| 4 | Wall strings | all three: A "Evergreen garland", B "Lantern string", C "Fairy lights" |
| 5 | Length of the winter festival | all December with a small gift each day, the peak on 24 to 26 Dec, New Year on 31 Dec to 1 Jan, take-down from 7 Jan |
| 6 | What the player does | all four: light the tree, a gift a day, the gathering, fireworks with the midnight show |
| 7 | Who comes to the gathering | everyone once per real day, off-duty colonists on the other game days |
| 8 | Default year length | 48 game days |

Not shown to him yet and still his to judge when they exist: the lights at night, the clothes (scarf colours, wool hat), the Decorator's skin, the gift and its contents, the sounds, the logo and page text.

## Owner answers of 20 Nov 2026 (after the in-game tests) [B]

- Doorless huts get one wreath on the outside wall nearest the hut block.
- Decorations the Decorator placed for free (and the gift pile and firework crate) drop nothing when broken; paid ones drop their item as before.
- On the day everyone comes to the gathering, guards stay at their posts; the other workers come.
- A second festival marker in a colony: the colony's managers get a message that only the first one counts.
- Take-down also removes a vanilla tree leaf whose only change is its distance property.
- New Year: the server launches the dusk and midnight shows from the firework crate with the Decorator's rockets while he sleeps.
- Auto-hire stays as in MineColonies (a new hut is filled last), with the README line; no chat notice.
- Ornament and tinsel leaves use the game's own spruce-leaf texture with the spruce tint and our ornaments on top (no copied Mojang pixels).
- Kept as built: every look the owner has seen (lights at night, blocks, scarves and hats, the Decorator's skin, the hut block, the five hut levels, the four trees, the gifts and sounds), logo A, the name Decorator's Workshop, hat colour equal to the scarf, no scarf over chest armour, tinsel leaves lit at night, mushroom-stem plaster, the own happiness reason shown in the hut window.
- Links in the mod (his decision of 20 Nov, see the supporter perks plan): Patreon https://www.patreon.com/cw/Lovkar and the Discord invite https://discord.gg/BVztrTtXFu in the mods.toml description with a short thank-you; one chat line the first time a player joins a world, once per player across all his mods (a shared neutral marker on the player), with a config switch: "Thanks for playing Lovkar's mods! News, help and test builds on Discord: discord.gg/BVztrTtXFu · Support the mods on Patreon: patreon.com/cw/Lovkar" (approved word for word); a "Discord / Support" line in the Decorator's Workshop window.
- The final page text goes to him for approval after the in-game test.

## Owner answers of 21 Nov 2026 [B]

- `showParticles` is removed from the client config (the mod has no particles of its own; nothing read it).
- The hut window says when the tree has no room at the festival marker; the managers' chat line stays (section 3.5).
- The storeroom blocks are named Storage Box and Coiled Decorations.
- The storeroom's fairy lights light with the tree.
- The storeroom layout of hut levels 2 to 4 stays as built.
- The switch `decorator.storeroomDressing` stays as built.
- The CurseForge page text (`release/curseforge-description.md`) is approved as written.

How S10i built the first two [A]: a client file that still has `showParticles` loads, and NeoForge drops the key when it corrects the file (csconfig seeded). The Calendar page shows one red line under "Now:" while the ledger notes that the tree did not fit (`DecorLedger.squareProblem`) and a decoration window is open; the note clears, and the line with it, when the tree fits on his next look.
