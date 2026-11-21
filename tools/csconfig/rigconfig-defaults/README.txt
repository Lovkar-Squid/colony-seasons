tools/probe.sh csconfig defaults picks this folder instead of rigconfig/.

It holds no .toml on purpose: nothing is seeded, NeoForge writes colony_seasons-server.toml from the mod's own
defaults, and the probe checks that the getters return the defaults of design section 9, written out by hand in
the probe. The probe also opens the client file colony_seasons-client.toml itself (a dedicated server never does):
NeoForge writes it from the defaults, and its keys must be the probe's hand-written client list (S10i).
