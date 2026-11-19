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
- **S3, the Decorator.** A new hut (`colony_seasons:blockhutdecorator`, building `colony_seasons:decorator`, levels 1 to 5,
  one worker, Creativity then Dexterity) and job (`colony_seasons:decorator`) with his own citizen model, skins for both
  genders and four skin tones, borrowed Florist voice lines, a hut block model in the look of the Workshop with
  shopfront, recipe, loot table and lang. All year (setting "prepare all year", on by default) he asks the colony for
  the materials of every decoration kit below the stock cap (8 + 8 x hut level per decoration) and assembles them at
  his hut; kits are datapack files in `data/<namespace>/decorator_kits/`, read by our own loader (a broken kit is
  skipped with one warning); `decorator.needsMaterials = false` makes him ask for and use nothing. The phase of his
  year comes from the calendar; the Decorating, Tending, Taking down, Hosting and Launching states are stubs for S4
  and S7. No hut blueprint yet (S5), so the hut cannot be built with the build tool until then. Probe `csdecorator`
  (three game days with a night and rain on a real colony, mode free) and `tools/restart.sh csdecorator`.
