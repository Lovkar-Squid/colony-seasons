# Changelog

## 0.1.0-beta.1 - unreleased - seasons and festivals for MineColonies

- **S0, skeleton and first boot.** The mod loads next to MineColonies 1.1.1420 on a dedicated server. It
  registers its (still empty) blocks, items, block entities, sounds, MineColonies jobs and buildings, and
  a server config `colony_seasons-server.toml` with every key of the design (calendar, the two festivals'
  dates, the Decorator, the events, the effects, all effects off). Build, probe and check scripts in `tools/`;
  the probes `csboot` and `csconfig` (defaults and seeded) pass.
- **S1, calendar.** The server knows the season and the active festivals, saves them with the world
  (`colony_seasons_calendar`: the offset and a forced festival), tells every client at login and on each change
  (`SeasonStatePayload`), and obeys `/colonyseasons status`, `season set <season>`, `festival force <id>` and
  `festival clear` (permission 2). Festival windows come from the config dates (year turn and 29 Feb handled);
  the season is `(day time / 24000 + offset) mod year length`; a new world starts in `calendar.startSeason`
  (`auto`: the season of the real date). Serene Seasons is followed through a reflection-only bridge, polled every
  20 ticks, with a one-line fall-back to the own calendar. The real clock is read in one class (`DateSource`).
  The chat line to a colony's players when the season turns is not in S1 (it needs colony lookups). Probe `cscalendar`
  (modes dates, bridge-absent, bridge-present, bridge-throws, bridge-nonseasonal, bridge-off), `tools/restart.sh`
  (two server runs on one kept world), a written-here Serene Seasons stand-in for the rig in `tools/sereneshim`.
- **S6, winter clothes.** Colonists and visitors wear a knitted scarf in in-game winter (red, green, blue, mustard or plum, picked by the colonist's id so he keeps it) and a striped festive scarf while the Winter Festival is on; fourteen MineColonies models without headgear of their own (`assets/colony_seasons/clothes/hat_models.json`) also get a wool hat of the scarf's colour in winter, never over a helmet or MineColonies' own December hat. Drawn by one client-only render layer on MineColonies' citizen renderer, nothing saved on the colonist; client config `colony_seasons-client.toml` (`showClothes`, `showParticles`). The season packet now clamps an unknown season id. `tools/clothescheck.sh` (the rules without a game, the clothes data), probe `csclient`, textures from `tools/gen_clothes.py`, previews on MineColonies' models in `docs/renders/s6/` (`tools/preview.sh`).
