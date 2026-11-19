# Colony Seasons 0.1.0-beta.1

The first beta. For NeoForge 1.21.1 with MineColonies 1.1.1420 or newer in the 1.1 line.

## New

- A calendar for the whole world: four seasons on a game year of 48 days (configurable), starting in the
  season of the real date. Serene Seasons is followed when it is installed.
- Festivals on real dates: the Winter Festival (decorations from 1 December to 6 January, festival days 24 to
  26 December) and New Year (31 December and 1 January). All dates can be changed in the config.
- The Decorator, a new MineColonies worker with his own building, the Decorator's Workshop (levels 1 to 5).
  He collects materials from the warehouse all year, decorates the colony's buildings when a festival window
  opens, looks after the decorations while it lasts and takes them down afterwards. A higher level gives more
  decorations and a bigger tree.
- Eleven decoration blocks with crafting recipes: wreath, garland, lantern string, fairy lights, ornament
  leaves, tinsel leaves, tree star, large tree star, gift pile, firework crate and festival marker.
- The festival marker: place it on the square and the Decorator builds the tree there.
- Festival days: a gift a day from the pile at the tree, a gathering of the colonists at the tree on 24 to
  26 December, fireworks on New Year, and a midnight show if the server runs through midnight.
- Winter clothes: in the game's winter a knitted scarf in five colours and a wool hat on models without
  headgear; from 1 December to 6 January a striped scarf. Client side only.
- Three effects on the game (festival happiness, slower crops in winter, more food in winter), all off by
  default.
- Commands: `/colonyseasons status`, `season set`, `festival force`, `festival clear`, `decor list` and
  `decor remove`.

## Known limits

- Only the Winter Festival and New Year are in this version.
- The happiness bonus is not listed in the citizen window; the Decorator's hut window shows it.
- MineColonies' own crops probably do not take part in the winter slowdown.
- Streets are not decorated.
- English only.
