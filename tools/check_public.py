"""Is everything that is public really fit to be public?

Runs over the files git tracks (git ls-files) and fails on:

  1. a personal name, the Windows user name of whoever runs it, or a private term (a world name, say) in any
     tracked text file or file name, also spelled backwards, and in every commit reachable from HEAD (its
     message, its file names and its text files), because a push publishes the whole history, not the tip;
  2. a user-folder path (the Users folder of Windows or macOS, or /home on Linux) in any tracked text file;
  3. a machine path under D:/Claude in README.md, the changelogs or release/ (the build tooling may have them);
  4. in the public text (README.md, the changelogs, release/): hype words, emoji, em dashes, and the words of
     a religion or a holiday of one country (the festival names are neutral by design);
  5. a link in the public text to a file that is not tracked;
  6. release/summary.txt missing, or longer than 120 characters, or on more than one line;
  7. the logo named by logoFile in neoforge.mods.toml missing or not a 400x400 PNG.

The personal names are not written in this file in any form, because this file is public too. They come,
one per line, from tools/private_terms.local.txt (ignored by git, so it stays on the machine) and from the
environment variable CS_PRIVATE_TERMS (comma separated); the user name of whoever runs the check is always
added. The check always runs a self-test first, so a rule that stopped working would fail the run.

Run:  python -I tools/check_public.py
"""
import getpass
import os
import pathlib
import re
import struct
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

PRIVATE_TERMS_FILE = ROOT / "tools" / "private_terms.local.txt"

SUMMARY_LIMIT = 120
LOGO_SIZE = (400, 400)

HYPE = ("immersive", "seamless", "elevate", "unleash", "supercharge", "game-changing", "dive into",
        "whether you're", "whether you are", "whether you\u2019re")
# Names that tie a festival to one religion or country; the festivals here are neutral (design section 1).
NEUTRAL = ("christmas", "xmas", "santa", "easter", "hanukkah", "diwali", "ramadan", "thanksgiving")
EMOJI = re.compile("[\U0001F000-\U0001FAFF\u2600-\u27BF\u2B00-\u2BFF\uFE0F]")
EM_DASH = "\u2014"

USER_PATH = re.compile(r"[A-Za-z]:[\\/]+(?i:users)[\\/]|(?<![A-Za-z0-9_.~-])/Users/\w|(?<![A-Za-z0-9_.~-])/home/\w")
MACHINE_PATH = re.compile(r"[A-Za-z]:[\\/]+Claude\b", re.I)
LINK = re.compile(r"\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)|(?:src|href)=\"([^\"]+)\"")


# ---------------------------------------------------------------- file lists

def tracked_files():
    """Paths (forward slashes, relative to the repo) of every tracked file."""
    out = subprocess.run(["git", "-C", str(ROOT), "ls-files", "-z"], capture_output=True, check=True).stdout
    return sorted(name for name in out.decode("utf-8", "replace").split("\0") if name)


def read_text(rel):
    """The text of a tracked file, or None for a binary one (or one that is gone)."""
    path = ROOT / rel
    if not path.is_file():
        return None
    data = path.read_bytes()
    if b"\0" in data[:8192]:
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return None


def is_public(rel):
    """The files a visitor reads on GitHub or CurseForge."""
    return rel == "README.md" or rel == "docs/CHANGELOG.md" or rel.startswith("CHANGELOG") or rel.startswith("release/")


def is_neutral_scope(rel):
    return is_public(rel) or rel == "resources/META-INF/neoforge.mods.toml" or (
        rel.startswith("resources/assets/") and "/lang/" in rel)


# ---------------------------------------------------------------- the rules

def private_terms():
    """Every term that must appear nowhere: this PC's user name and the private list (file and environment)."""
    terms = set()
    for candidate in (getpass.getuser(), pathlib.Path.home().name, os.environ.get("USERNAME", "")):
        if candidate and len(candidate) >= 3:
            terms.add(candidate.lower())
    if PRIVATE_TERMS_FILE.is_file():
        terms.update(line.strip().lower() for line in PRIVATE_TERMS_FILE.read_text(encoding="utf-8").splitlines()
                     if line.strip() and not line.startswith("#"))
    terms.update(item.strip().lower() for item in os.environ.get("CS_PRIVATE_TERMS", "").split(",") if item.strip())
    return sorted(terms)


def find_private(rel, text, terms):
    """A private term in the text, forwards or backwards (a name spelled backwards is still the name)."""
    low = text.lower()
    problems = []
    for term in terms:
        if term in low:
            problems.append(f"{rel}: contains the private term {term!r}")
        elif term[::-1] in low:
            problems.append(f"{rel}: contains the private term {term!r}, spelled backwards")
    return problems


def history_objects(rev="HEAD"):
    """(kind, id, path, bytes) of every commit, tree and blob reachable from rev, each object once."""
    git = ["git", "-C", str(ROOT)]
    listing = subprocess.run(git + ["rev-list", "--objects", rev], capture_output=True, check=True).stdout
    paths = {}
    for line in listing.decode("utf-8", "replace").splitlines():
        oid, _, path = line.partition(" ")
        if oid:
            paths.setdefault(oid, path)
    if not paths:
        return []
    batch = subprocess.run(git + ["cat-file", "--batch"], input=("\n".join(paths) + "\n").encode(),
                           capture_output=True, check=True).stdout
    objects, pos = [], 0
    while pos < len(batch):
        end = batch.index(b"\n", pos)
        oid, kind, size = batch[pos:end].decode().split()
        body = batch[end + 1:end + 1 + int(size)]
        pos = end + 1 + int(size) + 1
        objects.append((kind, oid, paths.get(oid, ""), body))
    return objects


def find_private_in_history(objects, terms):
    """Private terms in commit messages, file names and text files of the history (trees and binaries skipped)."""
    problems = []
    for kind, oid, path, body in objects:
        where = f"history {kind} {oid[:10]}" + (f" ({path})" if path else "")
        if path:
            problems += find_private(where + " file name", path, terms)
        if kind == "commit" or (kind == "blob" and b"\0" not in body[:8192]):
            problems += find_private(where, body.decode("utf-8", "replace"), terms)
    return problems


def find_user_paths(rel, text):
    return [f"{rel}: contains a user-folder path ({m.group(0)!r})" for m in USER_PATH.finditer(text)]


def find_machine_paths(rel, text):
    if not is_public(rel):
        return []
    return [f"{rel}: contains a machine path ({m.group(0)!r}); public text has none" for m in MACHINE_PATH.finditer(text)]


def find_style(rel, text):
    problems = []
    if is_public(rel):
        low = text.lower()
        problems += [f"{rel}: hype word {word!r}" for word in HYPE if word in low]
        if EMOJI.search(text):
            problems.append(f"{rel}: contains an emoji")
        if EM_DASH in text:
            problems.append(f"{rel}: contains an em dash")
    if is_neutral_scope(rel):
        low = text.lower()
        problems += [f"{rel}: the word {word!r} (festival names stay neutral)" for word in NEUTRAL if word in low]
    return problems


def find_dead_links(rel, text, tracked):
    if not is_public(rel) or not rel.endswith(".md"):
        return []
    problems = []
    base = pathlib.PurePosixPath(rel).parent
    known = set(tracked)
    for match in LINK.finditer(text):
        target = (match.group(1) or match.group(2) or "").split("#")[0]
        if not target or re.match(r"^[a-z][a-z0-9+.-]*:", target, re.I):
            continue
        resolved = pathlib.PurePosixPath(os.path.normpath(str(base / target)).replace("\\", "/"))
        if str(resolved) not in known:
            problems.append(f"{rel}: links to {target!r}, which is not a tracked file")
    return problems


def find_summary_problems(text):
    if text is None:
        return ["release/summary.txt is missing"]
    body = text.strip()
    problems = []
    if not body:
        problems.append("release/summary.txt is empty")
    if "\n" in body:
        problems.append("release/summary.txt has more than one line")
    if len(body) > SUMMARY_LIMIT:
        problems.append(f"release/summary.txt is {len(body)} characters, the limit is {SUMMARY_LIMIT}")
    return problems


def png_size(data):
    if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        return None
    return struct.unpack(">II", data[16:24])


def find_logo_problems():
    toml = ROOT / "resources" / "META-INF" / "neoforge.mods.toml"
    if not toml.is_file():
        return ["resources/META-INF/neoforge.mods.toml is missing"]
    match = re.search(r'^logoFile\s*=\s*"([^"]+)"', toml.read_text(encoding="utf-8"), re.M)
    if not match:
        return ["neoforge.mods.toml has no logoFile line"]
    logo = ROOT / "resources" / match.group(1)
    if not logo.is_file():
        return [f"logoFile {match.group(1)!r} is not in resources/"]
    size = png_size(logo.read_bytes())
    if size is None:
        return [f"{match.group(1)} is not a PNG"]
    if size != LOGO_SIZE:
        return [f"{match.group(1)} is {size[0]}x{size[1]}, the logo must be {LOGO_SIZE[0]}x{LOGO_SIZE[1]}"]
    return []


# ---------------------------------------------------------------- self-test

def selftest():
    """Every rule must flag a bad sample and let a good one through; otherwise the run fails."""
    failures = []

    def expect(name, found, wanted):
        if bool(found) != wanted:
            failures.append(f"self-test '{name}': expected {'a problem' if wanted else 'none'}, got {found}")

    expect("private term hit", find_private("README.md", "Hi Zebedee", ["zebedee"]), True)
    expect("private term clean", find_private("README.md", "Made by Lovkar & Claude", ["zebedee"]), False)
    expect("private term backwards", find_private("tools/x.py", 'NAMES = ("eedebez",)', ["zebedee"]), True)
    expect("history commit message", find_private_in_history(
        [("commit", "c" * 40, "", b"tree t\n\nthanks Zebedee\n")], ["zebedee"]), True)
    expect("history old blob", find_private_in_history(
        [("blob", "b" * 40, "tools/x.py", b'NAMES = ("eedebez",)')], ["zebedee"]), True)
    expect("history file name", find_private_in_history(
        [("blob", "b" * 40, "docs/zebedee.md", b"ok")], ["zebedee"]), True)
    expect("history binary and tree skipped", find_private_in_history(
        [("blob", "b" * 40, "a.png", b"\x89PNG\0zebedee"), ("tree", "t" * 40, "docs", b"100644 zebedee\0")],
        ["zebedee"]), False)
    expect("history clean", find_private_in_history(
        [("commit", "c" * 40, "", b"tree t\n\nS11: pages\n"), ("blob", "b" * 40, "README.md", b"Lovkar & Claude")],
        ["zebedee"]), False)
    expect("windows user path, lower case", find_user_paths("a.md", "see c:/" + "users/someone"), True)
    expect("windows user path", find_user_paths("a.md", "see C:/" + "Users/someone/x"), True)
    expect("unix user path", find_user_paths("a.md", "in /" + "home/someone/x"), True)
    expect("mac user path", find_user_paths("a.md", "in /" + "Users/someone"), True)
    expect("tilde path ok", find_user_paths("a.md", "see ~/x and %USERPROFILE%\\x"), False)
    expect("machine path in README", find_machine_paths("README.md", "at D:/Claude/rig"), True)
    expect("machine path in tooling ok", find_machine_paths("tools/rigpaths.sh", "D:/Claude/rig"), False)
    expect("machine path in release", find_machine_paths("release/VERIFY.md", "D:\\Claude\\rig"), True)
    expect("hype word", find_style("README.md", "An Immersive mod"), True)
    expect("whether you're", find_style("release/curseforge-description.md", "Whether you're new"), True)
    expect("hype word in tooling ok", find_style("tools/x.py", "seamless"), False)
    expect("emoji", find_style("README.md", "# Snow \U0001F384"), True)
    expect("em dash", find_style("docs/CHANGELOG.md", "one \u2014 two"), True)
    expect("neutral word in lang", find_style("resources/assets/colony_seasons/lang/en_us.json", '"x": "Christmas"'), True)
    expect("neutral word in design ok", find_style("docs/design.md", "a Santa hat"), False)
    expect("dead link", find_dead_links("README.md", "![x](docs/nothing.png)", ["README.md"]), True)
    expect("live link", find_dead_links("README.md", "[b](BUILDING.md#x)", ["README.md", "BUILDING.md"]), False)
    expect("web link ok", find_dead_links("README.md", "[b](https://example.org/a)", ["README.md"]), False)
    expect("img src dead", find_dead_links("release/a.md", '<img src="shots/a.png">', ["release/a.md"]), True)
    expect("summary too long", find_summary_problems("x" * (SUMMARY_LIMIT + 1)), True)
    expect("summary ok", find_summary_problems("x" * SUMMARY_LIMIT + "\n"), False)
    expect("summary missing", find_summary_problems(None), True)
    expect("summary two lines", find_summary_problems("a\nb"), True)
    expect("png size wrong", png_size(b"\x89PNG\r\n\x1a\n\0\0\0\rIHDR" + struct.pack(">II", 64, 64)) == LOGO_SIZE, False)
    expect("png size right", png_size(b"\x89PNG\r\n\x1a\n\0\0\0\rIHDR" + struct.pack(">II", 400, 400)) == LOGO_SIZE, True)
    return failures


# ---------------------------------------------------------------- main

def main():
    problems = selftest()
    if problems:
        print("\n".join(problems))
        print("check_public: the self-test failed, the rules above do not work")
        return 1

    tracked = tracked_files()
    terms = private_terms()
    for rel in tracked:
        problems += find_private("file name " + rel, rel, terms)
        text = read_text(rel)
        if text is None:
            continue
        problems += find_private(rel, text, terms)
        problems += find_user_paths(rel, text)
        problems += find_machine_paths(rel, text)
        problems += find_style(rel, text)
        problems += find_dead_links(rel, text, tracked)
    problems += find_private_in_history(history_objects("HEAD"), terms)

    summary = ROOT / "release" / "summary.txt"
    problems += find_summary_problems(summary.read_text(encoding="utf-8") if summary.is_file() else None)
    for needed in ("README.md", "CHANGELOG-0.1.0-beta.1.md", "release/curseforge-description.md", "release/VERIFY.md"):
        if needed not in tracked:
            problems.append(f"{needed} is not tracked (missing or not added to git)")
    problems += find_logo_problems()

    if problems:
        print("\n".join(problems))
        print(f"check_public: {len(problems)} problem(s)")
        return 1
    note = "" if PRIVATE_TERMS_FILE.is_file() or os.environ.get("CS_PRIVATE_TERMS") else (
        "; no tools/private_terms.local.txt or CS_PRIVATE_TERMS here, so only the user name was checked")
    print(f"check_public: ok ({len(tracked)} tracked files, {len(terms)} private terms{note})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
