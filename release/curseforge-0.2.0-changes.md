# CurseForge page: what 0.2.0 changes

For the owner's approval. `curseforge-description.md` was approved word for word for 0.1.0. Everything below is either
NEW (a paragraph or row added) or CHANGED (a line that 0.2.0 made untrue, with the old line). Nothing else in the page
was touched.

## NEW: after the requirements paragraph

Trade Post for MineColonies is optional too: with it, the Town Square's festival stalls pay the colony. Nothing else changes without it.

## NEW: three sections, between "The tree and the gifts" and "Clothes"

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
goods there (each sale takes one good from his Workshop above one batch of it), and each sale pays the colony into Trade
Post's balance, at most one Trade Coin per stall per day. The mod keeps no money of its own. `compat.tradePostBridge =
false` turns the stalls off. Without Trade Post nothing changes.

## NEW: two rows in the config table, after `decorator.storeroomDressing`

| `decorator.streetLights` | false | true: thin fairy lights beside paths in the festival windows |
| `compat.tradePostBridge` | true | false: the Town Square's festival stalls and their payments are off |

## CHANGED: in "What it does not do", the streets

Old: `Streets are not decorated.`

New: `Streets are decorated only where you mark them with the Decorator's wand.`

Why: the wand now decorates the spots a player marks. The Decorator still never picks a street spot himself, and the
other sentences of the paragraph are unchanged, except the gathering line below.

## CHANGED: in "What it does not do", the gathering without a Workshop (added by the Q9 review)

Old: `the festival marker (or the town hall) on the Winter Festival days.`

New: `the Town Square or the festival marker (or the town hall) on the Winter Festival days.`

Why: a built Town Square is the colony's gathering place also without a Decorator (the gathering's place is the main
square's tree cell first, then the marker, then the town hall).

## CHANGED: the paragraph on removing the mod (added by the Q9 review)

Old: `the stone bricks, podzol and lanterns of the grand tree's planter.`

New: `the stone bricks, podzol and lanterns of the grand tree's planter. On a Town Square the stalls' posts, slabs and
wool and the planters' leaves or pumpkin stay too.`

Why: the stalls and the planters' touches are vanilla blocks, so they stay in the world if the mod is taken out
without `/colonyseasons decor remove` first (that command takes them down with the rest).

## Not changed on purpose

The 0.1.0 text about the festival marker still holds: the marker works, and a colony with only a marker behaves as before.
"From Workshop level 2 a gift pile stands at the foot of the tree" stays: it is true at the marker, and the new Town
Square section says that on the square the gift pile and the crate stand at their own places.
`release/summary.txt` (one line, at most 120 characters) is unchanged.
