"""Small synthesis kit shared by the festival sound scripts (tools/sfx/chime.py, tools/sfx/song.py).

Everything is made here from sine partials, noise and envelopes; nothing is sampled or copied. Sounds are mono
(Minecraft attenuates only mono sounds by distance), 44.1 kHz, and written as OGG Vorbis through ffmpeg, which must
be on the PATH. numpy and scipy are needed.
"""
import os
import subprocess
import sys
import wave

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # tools/, also under python -I
import paths  # noqa: E402

SR = 44100
SOUNDS = paths.res("assets", "colony_seasons", "sounds", "festival")
PREVIEWS = os.path.join(paths.ROOT, "docs", "renders", "s7")

A4 = 440.0
NOTE_INDEX = {"C": -9, "D": -7, "E": -5, "F": -4, "G": -2, "A": 0, "B": 2}


def hz(name):
    """'A4', 'F#5', 'Bb3' -> frequency in Hz (equal temperament)."""
    letter, rest = name[0], name[1:]
    shift = 0
    while rest and rest[0] in "#b":
        shift += 1 if rest[0] == "#" else -1
        rest = rest[1:]
    octave = int(rest)
    semis = NOTE_INDEX[letter] + shift + (octave - 4) * 12
    return A4 * 2 ** (semis / 12)


def silence(seconds):
    return np.zeros(int(seconds * SR))


def tone(freq, seconds, partials, decay, attack=0.004, detune=0.0):
    """A struck tone: sine partials (ratio, gain, decay factor), each decaying exponentially."""
    n = int(seconds * SR)
    t = np.arange(n) / SR
    out = np.zeros(n)
    for ratio, gain, speed in partials:
        f = freq * ratio * (1 + detune)
        out += gain * np.sin(2 * np.pi * f * t) * np.exp(-t * decay * speed)
    env = np.minimum(1.0, t / max(attack, 1e-4))
    return out * env


def place(track, sound, at_seconds, gain=1.0):
    """Adds `sound` into `track` starting at `at_seconds` (the track grows if needed)."""
    start = int(at_seconds * SR)
    end = start + len(sound)
    if end > len(track):
        track = np.concatenate([track, np.zeros(end - len(track))])
    track[start:end] += gain * sound
    return track


def reverb(x, seconds=0.9, mix=0.2, seed=7):
    """A soft room: convolution with an exponentially decaying noise burst (seeded, the same on every run)."""
    rng = np.random.default_rng(seed)
    n = int(seconds * SR)
    t = np.arange(n) / SR
    ir = rng.standard_normal(n) * np.exp(-t * 6.0 / seconds)
    ir[0] = 0.0
    ir /= np.sqrt(np.sum(ir ** 2)) + 1e-9
    from scipy import signal
    wet = signal.fftconvolve(x, ir)
    dry = np.concatenate([x, np.zeros(len(wet) - len(x))])
    return (1 - mix) * dry + mix * wet


def fade(x, seconds=0.25):
    n = min(len(x), int(seconds * SR))
    x = x.copy()
    x[-n:] *= np.linspace(1.0, 0.0, n)
    return x


def normalise(x, peak=0.7):
    m = np.max(np.abs(x))
    return x if m == 0 else x * (peak / m)


# no encoder tag, no metadata, a fixed stream serial: the same bytes on every run
EXACT = ["-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", "-serial_offset", "1"]


def write(name, samples, preview_seconds=None):
    """Writes sounds/festival/<name>.ogg and the owner's preview docs/renders/s7/<name>.ogg (cut to preview_seconds)."""
    os.makedirs(SOUNDS, exist_ok=True)
    os.makedirs(PREVIEWS, exist_ok=True)
    wav = paths.out(f"sfx_{name}.wav")
    pcm = np.clip(samples, -1.0, 1.0)
    with wave.open(wav, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((pcm * 32767).astype("<i2").tobytes())
    target = os.path.join(SOUNDS, f"{name}.ogg")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-ac", "1", "-c:a", "libvorbis", "-q:a", "5", *EXACT, target],
                   check=True)
    preview = os.path.join(PREVIEWS, f"{name}.ogg")
    cut = ["-t", str(preview_seconds)] if preview_seconds else []
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, *cut, "-ac", "1", "-c:a", "libvorbis", "-q:a", "5", *EXACT,
                    preview], check=True)
    print(f"{name}: {len(samples) / SR:.2f} s -> {os.path.relpath(target, paths.ROOT)} ({os.path.getsize(target)} bytes), "
          f"preview {os.path.relpath(preview, paths.ROOT)}")
    return target
