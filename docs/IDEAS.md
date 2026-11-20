# Ideas for later versions

## Town Square as a real building (owner's idea, 20 Nov 2026)

The square becomes a MineColonies building of its own, with a blueprint and levels, instead of the festival
marker block. The Decorator is employed at his workshop and works the square too, the way the Courier serves the
Warehouse. He decorates the square for the current season and festival, or in a style the player orders in the
square's window ("follow the season" or a chosen theme).

Why it fits:
- every later festival (Spring Bloom, Harvest Fair, Night of Lanterns) gets its own square set on the same spot;
- the square's blueprint can carry position tags (tree, stalls, lantern posts), so pieces go where they are meant
  to go instead of being fitted round a marker;
- square levels give a bigger, richer square, as the workshop's levels give a bigger tree now.

To check before designing: how MineColonies ties one worker to two buildings (Courier and Warehouse, the quarry
workers), and how a square building coexists with the existing festival marker and the ledger.

Proposed for 0.2.0, with the next festival before its real date. A short design round first.

Owner's addition (20 Nov 2026): when Trade Post for MineColonies is installed, the square can tie into it and help
the colony earn money (for example festival market stalls on the square). It must stay optional: a runtime bridge,
no compile dependency, the mod loads and works without Trade Post.

## Forester and the festival tree (owner, 20 Nov 2026)

The festival tree must not count as a tree for MineColonies' Forester, or he cuts it (free logs, and the Decorator
puts it back the next day). Planned fix S10c for 0.1.0: our own trunk block that looks like a spruce log but is not in
#minecraft:logs, the pieces rebuilt with it, and a rig probe with a Forester whose area holds the tree. The same rule
applies to the Town Square tree in 0.2.0.
