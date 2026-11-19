"""The real clock is read in one place: me/lovkar/seasons/calendar/DateSource.java (design 2.1, [B]).

Festival windows, the force command and every test depend on it being replaceable, so no other file of
src/ may ask the system for the time. This looks for the usual ways to do that and fails on any hit
outside DateSource.java.

Run:  python -I tools/check_clock.py
"""
import pathlib
import re
import sys

ALLOWED = "DateSource.java"
CLOCK = re.compile(
    r"System\.currentTimeMillis|System\.nanoTime|(?:Local(?:Date|DateTime|Time)|Instant|ZonedDateTime|OffsetDateTime|Year|YearMonth|MonthDay)\.now\s*\("
    r"|Clock\.system|new\s+Date\s*\(|Calendar\.getInstance")


def main():
    root = pathlib.Path(__file__).resolve().parent.parent / "src"
    bad = 0
    count = 0
    for java in sorted(root.rglob("*.java")):
        count += 1
        if java.name == ALLOWED:
            continue
        for number, line in enumerate(java.read_text(encoding="utf-8").splitlines(), 1):
            code = line.split("//")[0]
            if CLOCK.search(code):
                print(f"  !! {java.relative_to(root.parent)}:{number} reads the clock: {line.strip()}")
                bad += 1
    print(f"clock: {count} source file(s) checked, only {ALLOWED} may read the real clock")
    print("  ok" if bad == 0 else f"  {bad} use(s) outside {ALLOWED}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
