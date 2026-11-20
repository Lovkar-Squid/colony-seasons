# Colony Seasons

Seasons and festivals for MineColonies.

Colony Seasons gives your colony a calendar. The game has seasons, and the festivals come on real dates. In
December a new worker, the Decorator, hangs wreaths and garlands on the colony's buildings and builds a
decorated tree on the square. Colonists put on striped scarves, they gather at the tree on the festival
days, you pick up a small gift every day, and New Year ends with fireworks. After 6 January the Decorator
takes it all down again, and next year he does it again.

## The Decorator

The Decorator is a new MineColonies job with his own building, the Decorator's Workshop, levels 1 to 5. All
year he asks the warehouse for leaves, string, dye, paper, torches and glowstone dust and makes decorations
at his hut, so they are ready when the festival window opens. When it does, he walks from building to
building, town hall first, and puts up what his level allows. A level 1 workshop gives each building a
wreath on its main door. Higher levels add garland and lights beside it and give the square a bigger tree, up
to a grand tiered tree with a large star at level 5.

He only places blocks into empty air, inside his own colony's claim, and he writes down every block he
places. When the window ends he takes down exactly those blocks, and only if they are still as he left them.
Anything you built yourself is left alone.

If you would rather not run a supply chain for decorations, one setting makes his work free.

## The tree and the gifts

Place a Festival Marker on the square and the Decorator builds the tree there. When it is finished you are
told, and a right-click on it lights every ornament. From the first of December the pile at its foot holds
one small gift per player per day. On 24 to 26 December the colonists walk to the tree in the afternoon,
the Decorator hands out gifts, and the gift of those days is a better one. On 31 December and 1 January there
are fireworks at dusk, and a countdown and a big show at midnight if the server runs through it.

The gifts come from a loot table, so a pack maker can change what is in them.

## Clothes

In the game's winter colonists and visitors wear a knitted scarf in one of five colours, and a wool hat if
their model has no headgear of its own. From 1 December to 6 January the scarf is a striped one, whatever the
season in the game. It is all drawn on the client, nothing goes into an inventory and nothing is saved on the
colonist.

## Seasons and dates

The game year is 48 game days by default, twelve to a season, and you can change that. A new world starts in
the season of the real date. If Serene Seasons is installed, Colony Seasons follows its calendar. The
festivals follow the real date on the server, and every date can be changed in the config. `/colonyseasons
festival force winter_festival` starts a festival outside its dates, for a video or a test.

## Effects on the game

There are three switches, all off by default: a happiness bonus on festival days, slower crop growth in
winter, and more food eaten in winter. With them all off the mod changes only looks, sounds, the Decorator's
materials and the gifts.

## What it does not do

Only the Winter Festival and New Year are in this version. The other festivals come later, each before its
real date. Without a Decorator's Workshop there is no tree, no gifts and no fireworks, though the
colonists still dress for the season and gather at the festival marker (or the town hall) on the festival days. Streets are not decorated. The happiness bonus does not show in the
citizen window, only in the Decorator's hut window. MineColonies' own crops probably ignore the winter
slowdown. The text is English only.

If you remove the mod from a world, run `/colonyseasons decor remove` for each colony first. The decoration
blocks disappear with the mod (the tree's trunk too), but the spruce leaves of the tree stay in the world.

## Requirements

NeoForge 1.21.1 and MineColonies 1.1.1420 or newer in the 1.1 line, with Structurize and Domum Ornamentum.
Serene Seasons is optional. Install it on the server and on every client.

## Links

Source and changelog on GitHub: https://github.com/Lovkar-Squid/colony-seasons. Bug reports there or in the
comments here, with your `logs/latest.log`.

Made by Lovkar & Claude. GPL-3.0-or-later.
