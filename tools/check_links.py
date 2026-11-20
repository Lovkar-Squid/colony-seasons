"""The links of the owner's decision of 20 Nov 2026 (S10d), checked where they are written.

1. The mods.toml description has both links in full (https) and a thank-you.
2. The lang line of the Decorator's Workshop window says "Discord / Support" and has both links as the window shows
   them (discord.gg/BVztrTtXFu, patreon.com/cw/Lovkar).
3. The welcome line in links/Welcome.java (TEXT, its \\uXXXX escapes read as the characters they stand for) is the
   sentence the owner approved word for word, and the two URL constants are exact.
4. The README names both links and the switch links.showWelcome.

Run: python -I tools/check_links.py   (exit 1 on a failure)
"""
import json
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
DISCORD = "https://discord.gg/BVztrTtXFu"
PATREON = "https://www.patreon.com/cw/Lovkar"
DISCORD_SHOWN = "discord.gg/BVztrTtXFu"
PATREON_SHOWN = "patreon.com/cw/Lovkar"
# approved by the owner on 20 Nov 2026, word for word (the dot is U+00B7)
APPROVED = ("Thanks for playing Lovkar's mods! News, help and test builds on Discord: discord.gg/BVztrTtXFu"
            " · Support the mods on Patreon: patreon.com/cw/Lovkar")
LANG_KEY = "com.colony_seasons.gui.info.links"


def java_string(text, name):
    """The value of `static final String NAME = "..." (+ "...")*;` with Java's escapes read."""
    match = re.search(r"static\s+final\s+String\s+" + name + r"\s*=\s*((?:\"(?:[^\"\\]|\\.)*\"\s*\+?\s*)+);", text)
    if not match:
        return None
    parts = re.findall(r"\"((?:[^\"\\]|\\.)*)\"", match.group(1))
    value = "".join(parts)
    value = re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), value)
    return value.replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\")


def problems():
    out = []
    toml = (REPO / "resources" / "META-INF" / "neoforge.mods.toml").read_text(encoding="utf-8")
    match = re.search(r"^description\s*=\s*'''(.*?)'''", toml, re.M | re.S)
    description = match.group(1) if match else ""
    for url in (DISCORD, PATREON):
        if url not in description:
            out.append(f"neoforge.mods.toml: the description does not have {url}")
    if "thank" not in description.lower():
        out.append("neoforge.mods.toml: the description has no thank-you")

    lang = json.loads((REPO / "resources" / "assets" / "colony_seasons" / "lang" / "en_us.json").read_text(encoding="utf-8"))
    line = lang.get(LANG_KEY, "")
    for part in ("Discord / Support", DISCORD_SHOWN, PATREON_SHOWN):
        if part not in line:
            out.append(f"en_us.json: {LANG_KEY} does not have {part!r}")
    window = (REPO / "src" / "me" / "lovkar" / "seasons" / "client" / "DecoratorInfoWindow.java").read_text(encoding="utf-8")
    if f'"{LANG_KEY}"' not in window:
        out.append(f"DecoratorInfoWindow.java does not show {LANG_KEY}")

    welcome = (REPO / "src" / "me" / "lovkar" / "seasons" / "links" / "Welcome.java").read_text(encoding="utf-8")
    text = java_string(welcome, "TEXT")
    if text != APPROVED:
        out.append(f"Welcome.TEXT is not the approved sentence: {text!r}")
    if java_string(welcome, "DISCORD_URL") != DISCORD:
        out.append("Welcome.DISCORD_URL is not " + DISCORD)
    if java_string(welcome, "PATREON_URL") != PATREON:
        out.append("Welcome.PATREON_URL is not " + PATREON)

    readme = (REPO / "README.md").read_text(encoding="utf-8")
    for part in (DISCORD, PATREON, "links.showWelcome"):
        if part not in readme:
            out.append(f"README.md does not have {part}")
    return out


def selftest():
    """The Java reader must read a split literal with an escape, or the check above proves nothing."""
    sample = 'static final String TEXT = "a \\u00b7 b"\n            + " c\\"d";'
    return [] if java_string(sample, "TEXT") == "a · b c\"d" else ["check_links: the self-test of the Java string reader failed"]


def main():
    found = selftest() + problems()
    for problem in found:
        print(f"  !! {problem}")
    print("  links: the mods.toml description, the window line, the welcome line and the README" if not found
          else f"  {len(found)} problem(s)")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
