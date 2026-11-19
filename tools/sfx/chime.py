"""The chime when a colony's tree is lit (festival/TreeLighting): about 3 s, mono.

A rising run of four struck bell tones (a major chord, G5 B5 D6 G6, glockenspiel-like partials) with a softer
octave under the last one, and a fine sparkle of tiny high pings on top, then a short room. Synthesized here from
sine partials and seeded noise; nothing is sampled.

Run:  python tools/sfx/chime.py   -> resources/assets/colony_seasons/sounds/festival/chime.ogg
                                        and the preview docs/renders/s7/chime.ogg
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import synth  # noqa: E402

# glockenspiel-like: the fundamental, a bright inharmonic pair and a quick metallic tick
BELL = [(1.0, 1.0, 1.0), (2.76, 0.35, 1.8), (5.40, 0.16, 3.0), (8.93, 0.06, 5.0)]
SOFT = [(1.0, 1.0, 1.0), (2.0, 0.25, 1.6), (3.0, 0.08, 2.5)]


def make():
    track = synth.silence(2.2)
    run = ["G5", "B5", "D6", "G6"]
    for i, note in enumerate(run):
        track = synth.place(track, synth.tone(synth.hz(note), 1.8, BELL, decay=2.6), 0.02 + i * 0.11, 0.55 + 0.1 * i)
    track = synth.place(track, synth.tone(synth.hz("G4"), 2.0, SOFT, decay=1.6), 0.35, 0.35)
    # the sparkle: thirty tiny high pings, seeded, thinning out
    rng = np.random.default_rng(20261219)
    for _ in range(30):
        at = 0.3 + rng.exponential(0.45)
        if at > 1.9:
            continue
        freq = rng.uniform(3200.0, 6400.0)
        ping = synth.tone(freq, 0.25, [(1.0, 1.0, 1.0), (2.0, 0.3, 1.5)], decay=22.0, attack=0.001)
        track = synth.place(track, ping, at, rng.uniform(0.04, 0.1))
    track = synth.reverb(track, seconds=0.8, mix=0.22, seed=11)
    return synth.fade(synth.normalise(track, 0.72), 0.3)


if __name__ == "__main__":
    synth.write("chime", make())
