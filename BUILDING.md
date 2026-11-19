# Building Colony Seasons

Plain `javac` and `jar` against the jars of the target modpack - no Gradle, no mappings step,
because NeoForge 1.21.1 mods run on official (Mojang) names. The version lives in
`resources/META-INF/neoforge.mods.toml` (`version="..."` on a line of its own, from column 0:
the build script reads it with a regex).

## What you need

- JDK 21
- NeoForge 21.1.x (the universal and the client jar), the Minecraft 1.21.1 client jar and its assets
  (`client-...-extra.jar`), and the libraries Minecraft links against (DataFixerUpper, fastutil, Guava,
  Gson, SLF4J, commons-lang3, JOML, the JetBrains annotations jar)
- MineColonies 1.1.1420, Structurize, BlockUI and Domum Ornamentum of the same pack
- `stubsrc/` holds two tiny stand-ins (`net.neoforged.api.distmarker.Dist` / `OnlyIn`) for the case that
  your NeoForge jars do not carry them; they are compiled into `stubs/` first

None of these jars are part of the repository (they are not ours to redistribute). On the author's PC they
sit in the shared test rig and the library folder named in `tools/rigpaths.sh`; that one file is the only
place where machine paths live, so building elsewhere means editing it.

## Build

```bash
bash tools/build.sh      # compiles src/, packs resources/, writes colony_seasons-<version>.jar at the repo root
bash tools/checks.sh     # lang keys, model textures, and a sequential read of the jar; prints ALL OK
```

`tools/build.sh` calls the rig's `build-addon.ps1`, which is a plain `javac` plus `jar` run. The jar is
read back front to back (`tools/JarCheck.java`) because a launcher may have to read it that way.

## Headless tests (probes)

A probe is a tiny server-side mod in `tools/<probe>/` that runs on a dedicated NeoForge test server (fresh
flat world, MineColonies and its libraries loaded) and logs `[<probe>] CHECK <what>: OK|FAIL` lines and last
`[<probe>] RESULT <probe>: PASS|FAIL`, then halts the server. Every probe of this mod starts with `cs`.

```bash
bash tools/probe.sh csboot                # the mod loads, every register is wired, the config spec is registered
bash tools/probe.sh csconfig defaults     # every getter equals the hand-written defaults list
bash tools/probe.sh csconfig seeded       # a config file with a non-default value for every key is read back
bash tools/probe.sh csclient              # the client config is registered, not loaded on a server, answers its defaults; the season packet clamps a bad id
```

```bash
bash tools/probe.sh cscalendar dates          # festival windows, seasons, saved data, the packet, the commands (fake clock)
bash tools/probe.sh cscalendar bridge-absent  # Serene Seasons not installed: own calendar, one log line
bash tools/probe.sh cscalendar bridge-present # a written-here stand-in jar (tools/sereneshim): the season follows it
bash tools/probe.sh cscalendar bridge-throws  # a stand-in that throws: falls back, one WARN line, no error-level line
bash tools/probe.sh cscalendar bridge-nonseasonal   # the stand-in's whitelist leaves the overworld out: falls back
bash tools/probe.sh cscalendar bridge-off     # calendar.useSereneSeasons = false: own calendar, no log line
bash tools/restart.sh cscalendar              # two server runs on one kept world: saved state is read back
```

`tools/probe.sh <probe> [mode]` builds the mod and the probe, then runs the rig. A probe folder may hold
`res/` (its own `neoforge.mods.toml`), `rigjars.txt` / `rigjars-<mode>.txt` (extra jars for every mode or one; `%REPO%` is this repo), `prepare.sh` (builds them first), `rigconfig/` (a config file seeded into the
fresh world) and `rigconfig-<mode>/` (the same for one mode). The run fails on a probe `FAIL` and on any `ERROR`
or `Exception` line that names the mod or the probe. The rig runs one test server at a time, so a run may wait
for another one to finish. `tools/restart.sh <probe>` runs the probe's modes `restart-save` and `restart-load` on one kept world
(through `tools/rig/run-rig-keep.ps1`, a copy of the rig's runner that wipes the world once and holds the lock across both runs).
`tools/sereneshim/` is a few lines of our own that stand in for the Serene Seasons API in the bridge modes; its jar stays in `tools/out/`.

If the server dies with `FAILED TO BIND TO PORT`, Windows has reserved the rig's port
(`netsh interface ipv4 show excludedportrange protocol=tcp`). `bash tools/private-rig.sh` makes a copy of the
rig on another port next to the repository; `tools/rigpaths.sh` then uses it automatically.

## Winter clothes check (no game)

```bash
bash tools/clothescheck.sh   # ClothesRules + HatModels + Season compiled against Gson only: who wears what, MineColonies' December hat,
                             # the shipped hat allow-list; then tools/check_clothes.py (also a step of checks.sh)
```

`tools/check_clothes.py` reads the citizen model ids out of the MineColonies jar (`ModModelTypes.class`) and checks every id
in `hat_models.json` against them, every texture the clothes code names and its size, and that nothing is shipped into
MineColonies' `nether` style folder.

## Generated files

Chunks that generate resources (blueprints, textures, sounds) document their scripts here.

- `python -I tools/gen_clothes.py` paints the clothes textures, `resources/assets/colony_seasons/textures/entity/scarf_*.png` and
  `wool_hat_*.png` (64x32, the layout of `ScarfModel` / `WoolHatModel`). Pure Python, the same bytes on every run.
- Preview renders of the clothes on MineColonies' models (`docs/renders/s6/`), with Voyager's entity preview pipeline copied here:
  `bash tools/preview.sh` runs both steps: `tools/mc_models.py` plays every citizen model's `createMesh()` bytecode (`javap -c`, JDK from
  `$JDK_BIN`) into `tools/out/mc_models.json`, a copy of their boxes that stays out of the repository; then
  `tools/entity_preview.py` builds the sheets (our boxes are read out of the two model classes)
  and runs Blender headless on `tools/mcrender.py` (the jar and Blender paths are in `tools/rigpaths.sh`).
