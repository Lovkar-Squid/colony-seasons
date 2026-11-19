# Colony Seasons

*Seasons and festivals for MineColonies.*

Colony Seasons gives a MineColonies colony a calendar. The game has seasons, and the festivals come on
real dates. In December a new worker, the **Decorator**, hangs wreaths and garlands on the colony's
buildings and builds a decorated tree on the square. Colonists put on scarves and wool hats, they gather
at the tree on the festival days, the player picks up a small gift every day, and New Year ends with
fireworks. After 6 January the Decorator takes everything down again.

Made by **Lovkar & Claude** for NeoForge 1.21.1.

![The square with the tree and two decorated houses](docs/renders/s2/2026-11-19-s2-square-day.png)

Needs NeoForge 1.21.1 and MineColonies 1.1.1420 or newer in the 1.1 line (with Structurize and Domum
Ornamentum, which MineColonies needs anyway). Serene Seasons is optional. Install it on the server and
on every client.

## The calendar

There is one calendar per world, so neighbouring colonies always agree. The game year is 48 game days
long by default, twelve to a season, which is about four hours of play. A new world starts in the season
of the real date, so a colony founded in December starts in winter. You can change the year length, the
starting season and the time zone in the config.

Festivals do not follow the game year. They follow the real date on the server, and every date can be
changed in the config.

| What | Default dates | What happens |
| --- | --- | --- |
| Winter decorations | 1 December to 6 January | decorations go up from the first day and stay; the tree stands; one small gift a day |
| Winter Festival | 24 to 26 December | the gathering at the tree, a better gift |
| New Year | 31 December to 1 January | fireworks at dusk, and a countdown and a big show at midnight if the server runs through it |
| Take-down | from 7 January | the Decorator removes everything he put up |

If Serene Seasons is installed, Colony Seasons follows its calendar instead of its own and says so in
`/colonyseasons status`. It does this by reflection, so Serene Seasons is not a dependency. If anything goes
wrong while reading it, the mod goes back to its own calendar for the rest of the session and writes one line
to the log.

While the effects below are off, a season changes nothing in the world: no snow, no leaf colours, nothing
placed. What shows is the clothes, the season and the day in the Decorator's hut window and in
`/colonyseasons status`, and one chat line to the colony's players when the season turns.

## The Decorator

The Decorator is a new MineColonies job. His building is the **Decorator's Workshop**, levels 1 to 5, with
room for one worker. Creativity is his main skill and Dexterity the second. He needs no research, so a young
colony can join in the festival.

All year he asks the colony's warehouse for materials, such as leaves, string, dye, paper, torches and
glowstone dust, and assembles decorations at his hut so that stock is ready when the first day of the window
comes. The recipes are datapack files in `data/colony_seasons/decorator_kits/`, so a pack maker can change
them. The hut window lists what he is missing. If you would rather not run a supply chain, set
`decorator.needsMaterials` to false and decorating is free.

When the window opens he walks from building to building, town hall first and then the nearest, and puts up
what his hut level allows. A higher level means more decorations on each building and a bigger tree:

| Workshop level | On each building | On the square |
| --- | --- | --- |
| 1 | a wreath on the main door | small village spruce |
| 2 | and garland, two on each side | small village spruce, a pile of gifts |
| 3 | and lights, two on each side; garland three | full-size spruce, a firework crate |
| 4 | garland and lights four on each side | full-size spruce with more ornaments |
| 5 | up to six on each side, the second door too | the grand tiered tree with the large star |

While the window lasts he looks over his work once a game day, puts back what went missing and refills the
gift pile. On festival days he stands at the tree, hands out gifts and sets off the fireworks. After the
window he visits every building he decorated, takes his decorations down and keeps them in his hut for next
year.

### Where decorations go

There is no list of doors in MineColonies, so the Decorator finds them the way a player would. He looks
through the building for doors, works out which side is the outside, hangs the wreath above the door and
lays garland and lights in the rows next to it, as far as there is a wall to hang them on. A building with
no free wall gets nothing, which is fine. A building keeps its kind of lights: some get paper lanterns and
some get small coloured bulbs.

The Decorator only places a block into empty air, in loaded chunks, inside his own colony's claim. He
writes every block he places into a list that is saved with the world, and he only takes away a block that
is on that list and still looks exactly like what he put there. Something a player built, or something the
Builder cleared, is never touched. A building that is being built or upgraded is skipped, and his
decorations come off it first. If the colony has no Decorator any more when the window ends, the server
takes the decorations down by itself as the chunks load.

The decorations are ordinary blocks with crafting recipes, in a creative tab called Colony Seasons, so you
can decorate by hand as well. The Decorator ignores blocks you place yourself.

## The festival marker

The tree stands where you want it. Place a **Festival Marker** on the square and the Decorator builds the
tree there, facing the way the marker faces. Without a marker there is no tree, but the buildings are still
decorated. A colony has one marker. If something is in the way of the tree, he builds the rest when at most
a tenth of it is blocked and none of that is the trunk. Otherwise he builds nothing and the hut window says
there is not enough room.

## What the player does in December

- Watch the colony fill with decorations over a few game days, and place the festival marker where the tree
  should stand.
- Light the tree. When the Decorator has finished it, the colony's players are told. A right-click on the
  tree lights the ornaments, the star and the strings with a chime and a few rockets. If nobody does it
  within two game days, the Decorator lights it himself.
- Take one gift a day from the pile at the foot of the tree. A gift opens into a roll of a loot table
  (`colony_seasons:gifts/winter_daily`), so a pack maker can change what is in it.
- On 24 to 26 December, come to the gathering. In the afternoon of the game day the colonists walk to the
  tree, the Decorator hands out gifts, and a short tune plays. The gift of these days is better.
- On New Year, watch the fireworks from the square at dusk. The gift of the day holds a few rockets.

By default, once per real day every colonist comes to the gathering, so work stops for those afternoon hours
on one game day. On the other game days of the festival only colonists who are off duty come. You can
change that with `events.whoComes`. To bring working colonists too, the mod uses one small mixin that is
written to fail quietly: if a MineColonies update moves the method it hooks, the mod still loads and only
off-duty colonists come.

## Clothes

Colonists and visitors wear a knitted scarf in in-game winter, in red, green, blue, mustard or plum. The
colour is picked from the colonist's id, so he keeps it. While the Winter Festival is on, the scarf is a
striped festive one. Colonists whose model has no headgear of its own also get a wool hat in the colour of
the scarf. It is never drawn over a helmet, or when MineColonies shows its own December hat; the scarf
still is. Guards in a chestplate get no scarf.

This is drawn on the client only, as one extra layer on MineColonies' colonist renderer. Nothing is put in
an inventory slot and nothing is saved on the colonist. There is a client config `colony_seasons-client.toml`
with `showClothes` and `showParticles` if you want it off.

## Effects on the game

There are three switches in the server config, all off by default. With all three off the mod changes
looks, sounds, the materials the Decorator uses and the gifts, and nothing else.

| Switch | When on |
| --- | --- |
| `effects.festivalHappiness` | on festival days every colonist gets a happiness bonus |
| `effects.winterCropSlowdown` | in in-game winter, crops inside colony claims skip some of their growth steps |
| `effects.winterFoodNeed` | in in-game winter, colonists use more food |

## Config

The server config is `colony_seasons-server.toml`. Every key has a comment, and every default is a named
constant in `SeasonsConfig.java`.

| Section | What it holds |
| --- | --- |
| `calendar` | year length in game days, starting season, whether to follow Serene Seasons, time zone |
| `festivals` | for each festival: on or off, the first and last day of decorations, the first and last festival day |
| `decorator` | whether he needs materials, how many buildings he does per game day, the most decorations on one side |
| `events` | when the gathering starts and ends, who comes, the midnight show, gifts per player per day |
| `effects` | the three switches above and how strong each one is |

## Commands

| Command | Who | What it does |
| --- | --- | --- |
| `/colonyseasons status` | everyone | the season, the day, which calendar is in use, which festivals are on |
| `/colonyseasons season set <season>` | operators | moves the game calendar to a season |
| `/colonyseasons festival force <id>` | operators | starts a festival outside its dates; it stays on, and is saved with the world, until you clear it |
| `/colonyseasons festival clear` | operators | ends a forced festival |
| `/colonyseasons decor list` | operators | counts the decorations the Decorator has placed |
| `/colonyseasons decor remove <colony>` | operators | removes every decoration the list holds for that colony, by the same rule as the take-down |

The festival ids are `winter_festival` and `new_year`. With Serene Seasons installed, `season set` leaves
the game alone and says so; use Serene Seasons' own command.

## What it does not do

- Only the Winter Festival and New Year are in 0.1.0. Spring Bloom, Harvest Fair and Night of Lanterns come
  later, each before its real date.
- No Decorator, no decorations. Without his hut the colonists still dress for the season, and players still
  get the season line, but there is no tree, no gift pile and no fireworks.
- Streets are not decorated. MineColonies has no road network to hang things on, and solid blocks on a
  street can block colonists.
- Festival dates are read from the server's clock. On a hosted server in another time zone, set
  `calendar.timeZone`.
- The happiness bonus is not listed in the citizen window, because MineColonies only shows its own fixed
  list there. The total is right, and the Decorator's hut window shows the bonus.
- MineColonies' own crops grow through their own code and probably do not take part in the winter slowdown.
  Vanilla crops inside a claim do.
- The text is English only.

If you take the mod out of a world, run `/colonyseasons decor remove` for each colony first. The decoration
blocks disappear with the mod, but the logs and leaves of the tree stay where they are.

## Building from source

Plain `javac` and `jar`, no Gradle. The steps, what you need and the tests are in
[BUILDING.md](BUILDING.md). History: [docs/CHANGELOG.md](docs/CHANGELOG.md).

## Licence and credits

GPL-3.0-or-later, see [LICENSE](LICENSE). Made by Lovkar & Claude. The textures, models and sounds are our
own. MineColonies, Structurize and Domum Ornamentum are the work of their authors and are not part of this
repository.
