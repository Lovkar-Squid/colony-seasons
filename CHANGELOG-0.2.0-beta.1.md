# Colony Seasons 0.2.0-beta.1

The Town Square. For NeoForge 1.21.1 with MineColonies 1.1.1420 or newer in the 1.1 line, Structurize and Domum
Ornamentum. Serene Seasons and Trade Post for MineColonies are optional. Install it on the server and on every client.

A 0.1.0 world loads unchanged: the decoration record, the configs, the blocks and the blueprints all keep working, and
a colony with only a festival marker behaves as before.

## New

- The Town Square, a building of its own in five levels (21 x 21 blocks at every level): a paved plaza with benches,
  lamp posts, planters, a low edge wall, stall pads and, at level 5, a bandstand in the corner. Craft the hut block
  (the notice board), place it, and the Builder builds it.
- One Decorator is assigned to the square, on its Decorator page or by the usual auto-hire. He stays employed at his
  Workshop.
- The colony's main square (the built one of the highest level) takes over from the festival marker. The Decorator
  builds the Winter Festival's tree on the square's own tree cell, sized by the lower of the square's level and the
  Workshop's level, with a foot in the centre cell. The gift pile and the firework crate stand at their own places on
  the square.
- The square's order, in its window: Follow the calendar (the default), Always Winter Festival, or Bare. The order
  never changes the events: the gathering, the gifts and the fireworks follow the real calendar.
- The gathering is at the square: the colonists stand on its gathering spots, the hut is the colony's leisure site in
  the gathering hours, and the tree is lit and the New Year shows are launched from the square.
- The square's window has an info page: its level, the order, the set that is wanted and the one that stands and its
  state, the next change of set, the Decorator, and what the next level adds. The Workshop's Calendar page has a line
  about the square.
- The Decorator makes the New Year show's firework rockets himself (paper and gunpowder, up to 24) and up to two firework
  crates (a barrel and 3 rockets, from Workshop level 3; a crate never takes the show's rockets), and asks the colony
  for no rockets any more. The market stalls sell neither.
- Small seasonal touches in the square's planters between the festivals: flowering azalea in spring, azalea leaves in
  summer, a pumpkin in autumn, spruce boughs in winter.
- Moving from the festival marker: when a Town Square is built, the marker's tree comes down (the items go back to the
  Workshop), the managers get one chat line, and the Decorator builds the set on the square. The marker stays as the
  fallback if the square is removed.
- The Decorator's wand, a new item (a stick, a string and a red dye). Right-click in the air to pick a snowman, a
  snowman with a top hat, a lantern post, a garland between two posts or a seasonal planter; right-click a block to mark
  a spot; sneak and right-click to remove a mark. The Decorator puts these street pieces up in every festival window and
  takes them down afterwards; the marks stay. A colony has up to 64 marks, and the wand's holder sees them as outlines.
- New decoration blocks: the snowman, the snowman with a top hat and the lantern post (with items and recipes), and the
  garland between posts and the street lights, which only the Decorator places.
- `decorator.streetLights` (off by default): thin fairy lights on every third fence or wall post beside a path.
- Optional, with Trade Post for MineColonies installed: festival market stalls with striped awnings on the square's
  stall pads (level 4 and 5). On festival days colonists buy the Decorator's festival goods there, and each sale pays
  the colony into Trade Post's balance, at most one Trade Coin per stall per day. `compat.tradePostBridge = false`
  turns it off. Without Trade Post nothing changes.
- `/colonyseasons decor list <colony>` names the colony's centrepiece (the Town Square, the festival marker or none) and
  lists the marked spots and the street lights.

## Changed

- Streets are no longer left bare when you want them decorated: the wand marks the spots. The Decorator still never
  picks a street spot himself.
- The decoration record is format 3. Files of 0.1.0 load unchanged. 0.1.0 can read a 0.2.0 file, and skips what it does
  not know with one warning.

## Known limits

- Only the Winter Festival and New Year are in this version.
- Only the colony's main Town Square is decorated; a second square is told so.
- The stalls sell festival goods only, and need Trade Post for MineColonies.
- The happiness bonus is not listed in the citizen window; the Decorator's hut window shows it.
- MineColonies' own crops grow through their own code and are not slowed in winter.
- English only.
