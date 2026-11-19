"""The short song of the gathering at the tree (festival/Gathering): about 14 s, mono.

An eight-bar waltz of our own in D major (3/4, 120 beats a minute): a music-box melody, soft bell chords on the
second and third beat and a warm bass on the first, then a short room. The tune was written for this mod (the
melody below, bar by bar); synthesized here from sine partials, nothing sampled or copied.

Run:  python tools/sfx/song.py   -> resources/assets/colony_seasons/sounds/festival/song.ogg
                                       and the preview docs/renders/s7/song.ogg
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import synth  # noqa: E402

BEAT = 0.5          # seconds: 120 beats a minute
# the melody: (note, beats), three beats a bar
MELODY = [
    ("F#5", 1), ("A5", 1), ("D6", 1),
    ("C#6", 2), ("B5", 1),
    ("A5", 1), ("B5", 1), ("A5", 1),
    ("F#5", 3),
    ("G5", 1), ("B5", 1), ("E6", 1),
    ("D6", 2), ("C#6", 1),
    ("B5", 1), ("A5", 1), ("E5", 1),
    ("D5", 3),
]
# one chord a bar: (bass, the two upper notes)
CHORDS = [("D3", "F#4", "A4"), ("G2", "G4", "B4"), ("A2", "E4", "A4"), ("D3", "F#4", "A4"),
          ("G2", "G4", "B4"), ("D3", "F#4", "A4"), ("A2", "E4", "G4"), ("D3", "F#4", "A4")]

MUSIC_BOX = [(1.0, 1.0, 1.0), (2.0, 0.22, 2.0), (3.0, 0.08, 3.0), (4.2, 0.05, 5.0)]
BELL = [(1.0, 1.0, 1.0), (2.0, 0.18, 1.8), (2.76, 0.06, 3.0)]
BASS = [(1.0, 1.0, 1.0), (2.0, 0.3, 1.4), (3.0, 0.1, 2.0)]


def make():
    track = synth.silence(len(CHORDS) * 3 * BEAT + 1.0)
    at = 0.0
    for note, beats in MELODY:
        length = beats * BEAT + 0.6
        track = synth.place(track, synth.tone(synth.hz(note), length, MUSIC_BOX, decay=2.2 if beats == 1 else 1.4), at, 0.5)
        at += beats * BEAT
    for bar, (bass, low, high) in enumerate(CHORDS):
        start = bar * 3 * BEAT
        track = synth.place(track, synth.tone(synth.hz(bass), 1.4, BASS, decay=2.0, attack=0.01), start, 0.42)
        for beat in (1, 2):
            for n in (low, high):
                track = synth.place(track, synth.tone(synth.hz(n), 0.6, BELL, decay=5.0, attack=0.006), start + beat * BEAT, 0.14)
    # the last chord rings on
    end = len(CHORDS) * 3 * BEAT
    for n in ("D4", "F#4", "A4", "D5"):
        track = synth.place(track, synth.tone(synth.hz(n), 1.6, BELL, decay=1.8), end - 3 * BEAT, 0.12)
    track = synth.reverb(track, seconds=1.0, mix=0.2, seed=23)
    return synth.fade(synth.normalise(track, 0.7), 0.6)


if __name__ == "__main__":
    synth.write("song", make())
