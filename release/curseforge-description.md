# Colony Seasons

Seasons and festivals for MineColonies.

Colony Seasons gives your colony a calendar. The game has seasons, and the festivals come on real dates. In
December a new worker, the Decorator, hangs wreaths, garlands and lights on the colony's buildings and builds a
decorated tree on the square. Colonists put on striped scarves, they gather at the tree on the Winter Festival days
(24 to 26 December), you pick up a small gift every day, and New Year ends with fireworks. After 6 January the
Decorator takes it all down again, and next year he does it again.

**Requires:** NeoForge 1.21.1 and MineColonies 1.1.1420 or newer in the 1.1 line, with Structurize and Domum
Ornamentum. Serene Seasons is optional. Install it on the server and on every client.
Trade Post for MineColonies is optional too: with it, the Town Square's festival stalls pay the colony. Nothing else changes without it.

## The Decorator

The Decorator is a new MineColonies job with his own building, the Decorator's Workshop, levels 1 to 5. All year
he keeps a stock of the decorations he makes ready (if you switch off the Workshop's "Prepare all year", only once
the decoration window is open). He takes them first from his own storage (last year's, taken down), then asks the
warehouse for finished decorations the colony already has, and only for the rest orders materials such as leaves,
string, dye, paper, torches, gunpowder and glowstone dust and makes them at his hut. He also makes the New Year show's
firework rockets (paper and gunpowder, up to 24) and up to two firework crates (a barrel and three rockets, from
Workshop level 3, never from the show's rockets), so the show needs no rockets from you. If you would rather not run a
supply chain, `decorator.needsMaterials = false` makes his work free, and he needs no stock.

When the festival window opens he walks from building to building, town hall first, and puts up what his level
allows. A level 1 Workshop gives each building a wreath above its main door. Higher levels add garland beside the
wreath and lights above it, and give the square a bigger tree, up to a grand tiered tree with a large star at
level 5. A building with no outside door gets one wreath on the wall nearest its hut block.

He only places blocks into empty air, inside his own colony's claim, and he writes down every block he places.
When the window ends he takes down exactly those blocks, and only if they are still as he left them. Anything you
built yourself is left alone. The tree's trunk is the mod's own block, so a Forester never cuts the festival tree.

## His Workshop

The Workshop keeps his stock in its racks, 2 at level 1 and 12 in the hut at level 5. At level 5, Upgrade
Storage makes every rack one row larger for one emerald block a step, three steps in all, like the Warehouse's
upgrade. He also dresses the Workshop's storeroom himself: outside the festival, boxes and coils of decorations
stand packed on its barrels; from the first day of the window until his take-down is done, the boxes are nearly
empty and garland, a wreath and fairy lights hang on its walls. `decorator.storeroomDressing = false` leaves the
room bare.

## The tree and the gifts

Place a Festival Marker on the square, inside the colony, and the Decorator builds the tree there. Only a marker
a player places by hand counts; one pasted with a blueprint or set with a command does not. When the tree is
finished you are told, and a right-click on it lights every ornament, the star and the lights on the buildings. If
nobody does within two game days, the Decorator lights it himself.

From Workshop level 2 a gift pile stands at the foot of the tree, and from the first of December each player of the
colony takes one small gift a day from it. On 24 to 26 December the colonists walk to the tree in the afternoon
(not when it rains), the Decorator hands them gifts, and your gift of those days is a better one. On 31 December
and 1 January there are fireworks at dusk, and a countdown and a big show at midnight if the server runs through
it. The gifts come from loot tables, so a pack maker can change what is in them.

## The Town Square

New in 0.2.0. The Town Square is a building of its own, levels 1 to 5, 21 x 21 blocks at every level: a paved plaza
with benches, lamp posts, planters, a low edge wall, stall pads and, at level 5, a bandstand in the corner. Craft the
hut block (the notice board), place it in the colony and let the Builder build it. One Decorator is assigned to it, in
its window or by the usual auto-hire, and he stays employed at his Workshop. The colony's main square (the built one of
the highest level) takes over from the festival marker: the Decorator builds the Winter Festival's tree on the square,
sized by the lower of the square's level and the Workshop's level, and the gift pile and the firework crate stand at
their own places on it. The gathering, the lighting of the tree and the New Year shows are at the square too.

In its Settings page you choose an order: Follow the calendar (the default), Always Winter Festival, or Bare. The order
never changes the events, which follow the real calendar. Between the festivals the Decorator puts the season's touch
in the square's planters: flowering azalea in spring, azalea leaves in summer, a pumpkin in autumn, spruce boughs in
winter. The square's window has an info page with its level, the order, what stands on the square and what the next
level adds.

A colony with only a festival marker works exactly as before. When a Town Square is built, the marker's tree comes down
(the items go back to the Workshop), the managers get one chat line, and the Decorator builds the set on the square. The
marker stays as the fallback if the square is removed.

## The Decorator's wand

New in 0.2.0. The Decorator's wand (a stick, a string and a red dye) marks spots for street decorations. Right-click in
the air to pick a snowman, a snowman with a top hat, a lantern post, a garland between two posts or a seasonal planter;
right-click a block to mark a spot; sneak and right-click a mark to remove it. In every festival window the Decorator
puts the pieces up, after the town hall and the square, and takes them down afterwards; the marks stay for next year. A
colony has up to 64 marks, and the wand's holder sees them as outlines. A mark must be inside the colony's claim, in
empty air, outside every building and at least 5 blocks from the festival marker, and you need the right to manage the
colony's huts. `decorator.streetLights` (off by default) adds thin fairy lights on every third fence or wall post beside
a path.

## Festival stalls (Trade Post for MineColonies)

New in 0.2.0, optional. With Trade Post for MineColonies installed, a Town Square of level 4 or 5 puts up festival
market stalls with striped awnings while a festival set stands. On festival days colonists buy the Decorator's festival
goods there (the decorations, never the rockets or the crates; each sale takes one good from his Workshop above one batch of it), and each sale pays the colony into Trade
Post's balance, at most one Trade Coin per stall per day. The mod keeps no money of its own. `compat.tradePostBridge =
false` turns the stalls off. Without Trade Post nothing changes.

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
food eaten in winter. With them all off the mod changes looks, sounds, the Decorator's materials, the gifts and
the gathering. By default, on each real day of 24 to 26 December, the working colonists (not the guards) leave work
for the gathering hours of one game day; `events.whoComes = off_duty` keeps them at work. On the New Year evenings
the Decorator stays up past his bedtime for the dusk show.

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
| `decorator.streetLights` | false | true: thin fairy lights beside paths in the festival windows |
| `compat.tradePostBridge` | true | false: the Town Square's festival stalls and their payments are off |
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

Only the Winter Festival and New Year are in this version; other festivals come later. Without a Decorator's
Workshop there is no tree, no gifts and no fireworks, though the colonists still dress for the season and gather at
the Town Square or the festival marker (or the town hall) on the Winter Festival days. Streets are decorated only where you mark them with the Decorator's wand. The happiness bonus
is in the happiness number of the citizen window, but the window does not list it among the reasons; the
Decorator's hut window shows it. MineColonies' own crops grow through their own code and are not slowed in winter.
The text is English only.

If you remove the mod from a world, run `/colonyseasons decor remove` for each colony first. The decoration blocks
disappear with the mod (the tree's trunk too), but the spruce leaves of the tree stay in the world, and at level 5
the stone bricks, podzol and lanterns of the grand tree's planter. On a Town Square the stalls' posts, slabs and
wool and the planters' leaves or pumpkin stay too.

## Links

Source and changelog on GitHub: https://github.com/Lovkar-Squid/colony-seasons. Bug reports there or in the comments here, with your `logs/latest.log`.

News, help and test builds on Discord: https://discord.gg/BVztrTtXFu. Support the mods on Patreon:
https://www.patreon.com/cw/Lovkar. The server sends a player these two links in one chat line the first time he
joins a world with the mod, once in that world; `links.showWelcome = false` turns the line off.

Made by Lovkar & Claude. GPL-3.0-or-later.
