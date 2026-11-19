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
```

`tools/probe.sh <probe> [mode]` builds the mod and the probe, then runs the rig. A probe folder may hold
`res/` (its own `neoforge.mods.toml`), `rigjars.txt` (extra jars), `rigconfig/` (a config file seeded into the
fresh world) and `rigconfig-<mode>/` (the same for one mode). The run fails on a probe `FAIL` and on any `ERROR`
or `Exception` line that names the mod or the probe. The rig runs one test server at a time, so a run may wait
for another one to finish.

If the server dies with `FAILED TO BIND TO PORT`, Windows has reserved the rig's port
(`netsh interface ipv4 show excludedportrange protocol=tcp`). `bash tools/private-rig.sh` makes a copy of the
rig on another port next to the repository; `tools/rigpaths.sh` then uses it automatically.

## Generated files

None yet. Chunks that generate resources (blueprints, textures, sounds) document their scripts here.
