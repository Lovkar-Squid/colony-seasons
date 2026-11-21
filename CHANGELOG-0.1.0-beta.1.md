# Colony Seasons 0.1.0-beta.1

The first beta. For NeoForge 1.21.1 with MineColonies 1.1.1420 or newer in the 1.1 line, Structurize and Domum
Ornamentum. Serene Seasons is optional. Install it on the server and on every client.

## New

- A calendar for the whole world: four seasons on a game year of 48 days (configurable), starting in the
  season of the real date. Serene Seasons is followed when it is installed.
- Festivals on real dates: the Winter Festival (decorations from 1 December to 6 January, festival days 24 to
  26 December) and New Year (31 December and 1 January). All dates can be changed in the config.
- The Decorator, a new MineColonies worker with his own building, the Decorator's Workshop (levels 1 to 5).
  He keeps a stock of decorations all year, decorates the colony's buildings when a festival window opens, looks
  after the decorations while it lasts and takes them down afterwards. A higher level gives more decorations and
  a bigger tree.
- His supply order: a decoration comes first from his own storage (last year's, taken down), then as finished
  decorations the colony's warehouses already hold (a courier brings them), and only for the rest does he order
  the materials and make it.
- The Workshop's storage room: 2, 3, 6, 9 and 12 racks at levels 1 to 5, and Upgrade Storage at level 5 (one
  emerald block a step, three steps, every rack one row larger), like the Warehouse's.
- The Workshop's storeroom, dressed by the Decorator for free: boxes and coils of decorations packed on its
  barrels outside the festival, nearly empty boxes and garland, a wreath and fairy lights on its walls in the
  festival window. `decorator.storeroomDressing = false` leaves it bare.
- Where decorations go: by level a wreath above the main door, garland beside it and lights above it; where an
  upper floor juts out over the door they hang on its front; a building with no door to the outside gets one
  wreath on the outside wall nearest its hut block.
- Eleven decoration blocks with crafting recipes: wreath, garland, lantern string, fairy lights, ornament
  leaves, tinsel leaves, tree star, large tree star, gift pile, firework crate and festival marker. Three more
  blocks only the Decorator places, with no item: the festival tree's trunk and the storeroom's boxes and coils.
- The festival marker: place it on the square and the Decorator builds the tree there. Only a marker a player
  places by hand counts. A second marker in a colony tells the colony's managers that only the first one counts.
- The tree's trunk is the mod's own block, in no log tag, so a Forester never cuts the festival tree.
- Festival days: a gift a day from the pile at the tree (from Workshop level 2), a gathering of the colonists at
  the tree on 24 to 26 December (the guards stay at their posts), fireworks on New Year, and a midnight show if
  the server runs through midnight.
- What the Decorator put up for free, and the tree's gift pile and firework crate, drop nothing when broken; what
  he paid for drops its item.
- Winter clothes: in the game's winter a knitted scarf in five colours and a wool hat on models without
  headgear; from 1 December to 6 January a striped scarf. Client side only.
- Three effects on the game (festival happiness, slower crops in winter, more food in winter), all off by
  default.
- One chat line with the Discord and Patreon links the first time a player joins a world with the mod, once per
  player in that world and shared with the author's other mods; `links.showWelcome = false` turns it off. The
  links are also at the foot of the Workshop's Calendar page.
- Commands: `/colonyseasons status`, `season set`, `festival force`, `festival clear`, `decor list` and
  `decor remove`.

## Known limits

- Only the Winter Festival and New Year are in this version.
- The happiness bonus is not listed in the citizen window; the Decorator's hut window shows it.
- MineColonies' own crops grow through their own code and are not slowed in winter.
- When the tree does not fit at the marker, the colony's managers get a chat line; the hut window does not say so.
- The client config's `showParticles` switch does nothing yet.
- Streets are not decorated.
- English only.
