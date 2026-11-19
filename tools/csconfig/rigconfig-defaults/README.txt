tools/probe.sh csconfig defaults picks this folder instead of rigconfig/.

It holds no colony_seasons-server.toml on purpose: nothing is seeded, NeoForge writes the file from the
mod's own defaults, and the probe checks that the getters return the defaults of design section 9,
written out by hand in the probe.
