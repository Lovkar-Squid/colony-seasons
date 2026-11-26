"""Is the cssquarelife probe's 0.1.0 reader really the 0.1.0 reader?

tools/cssquarelife/v010/DecorLedger.java is the decoration ledger as the published 0.1.0-beta.1 shipped it, with only
its package line changed, so the probe can load a file this build writes the way a 0.1.0 server would (the downgrade
of town-square-design.md section 8 point 13). This compares it, line for line, with the file at the git tag
v0.1.0-beta.1. Where git or the tag is not there (a source archive), it says so and passes.

Run: python -I tools/check_reader.py   (exit 1 on a difference)
"""
import pathlib
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
TAG = "v0.1.0-beta.1"
SOURCE = "src/me/lovkar/seasons/decor/DecorLedger.java"
COPY = REPO / "tools" / "cssquarelife" / "v010" / "DecorLedger.java"
PACKAGE_THEN = "package me.lovkar.seasons.decor;"
PACKAGE_NOW = "package me.lovkar.seasons.cssquarelife.v010;"


def lines(text):
    return text.replace("\r\n", "\n").split("\n")


def main():
    if not COPY.exists():
        print(f"  !! {COPY.relative_to(REPO)} is missing")
        return 1
    try:
        tagged = subprocess.run(["git", "-C", str(REPO), "show", f"{TAG}:{SOURCE}"], capture_output=True, check=True).stdout
    except (OSError, subprocess.CalledProcessError):
        print(f"  0.1.0 reader: git or the tag {TAG} is not available here; not compared")
        return 0
    then = lines(tagged.decode("utf-8"))
    now = lines(COPY.read_text(encoding="utf-8"))
    if not then or then[0] != PACKAGE_THEN:
        print(f"  !! the tagged file does not start with {PACKAGE_THEN}")
        return 1
    if not now or now[0] != PACKAGE_NOW:
        print(f"  !! the copy does not start with {PACKAGE_NOW}")
        return 1
    if then[1:] != now[1:]:
        for n, (a, b) in enumerate(zip(then, now), 1):
            if n > 1 and a != b:
                print(f"  !! line {n} differs from {TAG}: {b.strip()[:80]}")
                break
        else:
            print(f"  !! the copy has {len(now)} lines, the tagged file {len(then)}")
        return 1
    print(f"  0.1.0 reader: the probe's copy equals {SOURCE} at {TAG} but for its package line ({len(now)} lines)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
