"""No compile dependency on Trade Post for MineColonies (chunk Q10, docs/town-square-design.md 7.1 [B]).

The build puts every jar of the shared library folder on the compile classpath, Trade Post's included, so a direct
reference to one of its classes would compile without a word. This checks what the compiler cannot:
  - no class file of the built jar holds the package name of Trade Post, written with dots or with slashes (the one
    class the bridge asks for by name is put together at run time from parts);
  - no source line under src/ imports or names it.
The package name is itself put together here, so this file is not a hit of its own.

  python -I tools/check_nodeps.py [repo]
"""
import pathlib
import re
import sys
import zipfile

PARTS = ("com", "deathfrog")
DOTS = ".".join(PARTS).encode()
SLASHES = "/".join(PARTS).encode()


def check(repo):
    repo = pathlib.Path(repo)
    bad = []
    version = None
    for line in (repo / "resources/META-INF/neoforge.mods.toml").read_text(encoding="utf-8").splitlines():
        found = re.match(r'^version="([^"]*)"', line)
        if found:
            version = found.group(1)
            break
    jar = repo / f"colony_seasons-{version}.jar"
    checked = 0
    if jar.exists():
        with zipfile.ZipFile(jar) as z:
            for name in z.namelist():
                if name.endswith(".class"):
                    checked += 1
                    data = z.read(name)
                    if DOTS in data or SLASHES in data:
                        bad.append(f"{name}: names the Trade Post package")
    else:
        print(f"  {jar.name} not built: only the sources are checked")
    for java in sorted((repo / "src").rglob("*.java")):
        for number, text in enumerate(java.read_text(encoding="utf-8").splitlines(), 1):
            if DOTS.decode() in text or SLASHES.decode() in text:
                bad.append(f"{java.relative_to(repo)}:{number}: names the Trade Post package")
    for line in bad:
        print("  " + line)
    if not bad:
        print(f"  no reference in {checked} built classes and the sources")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(check(sys.argv[1] if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parent.parent))
