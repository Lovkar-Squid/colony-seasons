# Colony Seasons

Seasons and festivals for MineColonies.

Colony Seasons gives your colony a calendar. The game has seasons, and the festivals come on real dates. In
December a new worker, the Decorator, hangs wreaths, garlands and lights on the colony's buildings and builds a
decorated tree on the square. Colonists put on striped scarves, they gather at the tree on the festival days, you
pick up a small gift every day, and New Year ends with fireworks. After 6 January the Decorator takes it all down
again, and next year he does it again.

**Requires:** NeoForge 1.21.1 and MineColonies 1.1.1420 or newer in the 1.1 line, with Structurize and Domum
Ornamentum. Serene Seasons is optional. Install it on the server and on every client.

## The Decorator

The Decorator is a new MineColonies job with his own building, the Decorator's Workshop, levels 1 to 5. All year
he keeps a stock of decorations ready. He takes them first from his own storage (last year's, taken down), then
asks the warehouse for finished decorations the colony already has, and only for the rest orders materials such as
leaves, string, dye, paper, torches and glowstone dust and makes them at his hut. If you would rather not run a
supply chain, `decorator.needsMaterials = false` makes his work free.

When the festival window opens he walks from building to building, town hall first, and puts up what his level
allows. A level 1 Workshop gives each building a wreath above its main door. Higher levels add garland beside the
wreath and lights above it, and give the square a bigger tree, up to a grand tiered tree with a large star at
level 5. A building with no outside door gets one wreath on the wall nearest its hut block.

He only places blocks into empty air, inside his own colony's claim, and he writes down every block he places.
When the window ends he takes down exactly those blocks, and only if they are still as he left them. Anything you
built yourself is left alone. The tree's trunk is the mod's own block, so a Forester never cuts the festival tree.

## His Workshop

The Workshop keeps his stock in racks in a storage room, 2 racks at level 1 and 12 at level 5. At level 5,
Upgrade Storage makes every rack one row larger for one emerald block a step, three steps in all, like the
Warehouse's upgrade. He also dresses the storeroom himself: outside the festival, boxes and coils of decorations
stand packed on its barrels; from the first day of the window until his take-down is done, the boxes are nearly
empty and garland, a wreath and fairy lights hang on its walls. `decorator.storeroomDressing = false` leaves the
room bare.

## The tree and the gifts

Place a Festival Marker on the square, inside the colony, and the Decorator builds the tree there. Only a marker
a player places by hand counts; one pasted with a blueprint or set with a command does not. When the tree is
finished you are told, and a right-click on it lights every ornament, the star and the lights on the buildings. If
nobody does within two game days, the Decorator lights it himself.

From Workshop level 2 a gift pile stands at the foot of the tree, and from the first of December each player of
the colony takes one small gift a day from it. On 24 to 26 December the colonists walk to the tree in the
afternoon, the Decorator hands them gifts, and your gift of those days is a better one. On 31 December and 1
January there are fireworks at dusk, and a countdown and a big show at midnight if the server runs through it.
The gifts come from loot tables, so a pack maker can change what is in them.

## Clothes

In the game's winter colonists and visitors wear a knitted scarf in one of five colours, and a wool hat if their
model has no headgear of its own. From 1 December to 6 January the scarf is a striped one, whatever the season in
the game. It is all drawn on the client: nothing goes into an inventory and nothing is saved on the colonist.

## Seasons and dates

The game year is 48 game days by default, twelve to a season, and you can change that. A new world starts in the
season of the real date. If Serene Seasons is installed, Colony Seasons follows its calendar. The festivals follow
the real date on the server, and every date can be changed in the config.

## Effects on the game

Three switches, all off by default: a happiness bonus on festival days, slower crop growth in winter, and more
food eaten in winter. With them all off the mod changes only looks, sounds, the Decorator's materials and the
gifts.

## Commands

| Command | Who | What it does |
| --- | --- | --- |
| `/colonyseasons status` | everyone | the season, the day, the calendar in use, the festivals that are on |
| `/colonyseasons season set <season>` | operators | moves the game calendar to a season |
| `/colonyseasons festival force <id>` | operators | starts `winter_festival` or `new_year` outside its dates, until you clear it |
| `/colonyseasons festival clear` | operators | ends a forced festival |
| `/colonyseasons decor list [colony]` | operators | counts the decorations the Decorator has placed |
| `/colonyseasons decor remove <colony>` | operators | takes down everything he placed in that colony, by the take-down's rule |

## Config switches

In `colony_seasons-server.toml`:

| Key | Default | What it does |
| --- | --- | --- |
| `decorator.needsMaterials` | true | false: decorations are free |
| `decorator.storeroomDressing` | true | false: the Workshop's storeroom stays bare |
| `events.whoComes` | everyone_once_a_day | who comes to the gathering: everyone but the guards once per real day, else off-duty colonists (or `off_duty`, `everyone`) |
| `events.midnightShow` | true | the countdown and the midnight show on New Year |
| `events.giftsPerPlayerPerDay` | 1 | gifts a player takes per real day; 0 turns them off |
| `effects.festivalHappiness` | false | a happiness bonus on festival days |
| `effects.winterCropSlowdown` | false | crops in colony claims skip growth steps in winter |
| `effects.winterFoodNeed` | false | colonists use more food in winter |
| `links.showWelcome` | true | the one chat line with the Discord and Patreon links |

The calendar, the festival dates and the strength of each effect are in the same file. In the Workshop's window,
"Prepare all year", "Decorate" and one switch for each of wreaths, garlands, lights and the tree decide what he
does in that colony.

## What it does not do

Only the Winter Festival and New Year are in this version. The other festivals come later, each before its real
date. Without a Decorator's Workshop there is no tree, no gifts and no fireworks, though the colonists still dress
for the season and gather at the festival marker (or the town hall) on the festival days. Streets are not
decorated. The happiness bonus does not show in the citizen window, only in the Decorator's hut window.
MineColonies' own crops grow through their own code and are not slowed in winter. The text is English only.

If you remove the mod from a world, run `/colonyseasons decor remove` for each colony first. The decoration blocks
disappear with the mod (the tree's trunk too), but the spruce leaves of the tree stay in the world, and at level 5
the stone bricks, podzol and lanterns of the grand tree's planter.

## Links

Source and changelog on GitHub: https://github.com/Lovkar-Squid/colony-seasons. Bug reports there or in the
comments here, with your `logs/latest.log`.

News, help and test builds on Discord: https://discord.gg/BVztrTtXFu. Support the mods on Patreon:
https://www.patreon.com/cw/Lovkar. The server sends a player these two links in one chat line the first time he
joins a world with the mod, once in that world; `links.showWelcome = false` turns the line off.

Made by Lovkar & Claude. GPL-3.0-or-later.
