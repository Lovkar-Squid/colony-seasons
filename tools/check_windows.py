"""Does every pane a window looks for exist in its BlockUI layout, and does every layout belong to a window?

A window class finds its controls by id, `findPaneOfTypeByID("lines", ScrollingList.class)`, and BlockUI answers
null for an id that is not in the xml. The code then skips the control without a word (the window opens, empty).
Nothing at build time says so. This reads both sides: for every client window class that loads a layout from
`gui/<name>.xml`, each id it asks for has to be an `id="..."` in that file, and the file must be well-formed XML.
The other direction is checked too: an id in the xml that nothing asks for is only noted (a layout may hold
decoration), but a layout file no window loads is an error.

Run: python -I tools/check_windows.py [repo ...]   (exit 1 on a failure)
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

LAYOUT = re.compile(r'ResourceLocation\.fromNamespaceAndPath\([^,]+,\s*"(gui/[A-Za-z0-9_./]+\.xml)"')
ASKED = re.compile(r'findPaneOfTypeByID\(\s*"([A-Za-z0-9_]+)"')


def check(repo):
    repo = pathlib.Path(repo)
    gui_dirs = list(repo.glob("resources/assets/*/gui"))
    layouts = {}
    for gui in gui_dirs:
        for xml in gui.rglob("*.xml"):
            layouts[f"gui/{xml.relative_to(gui).as_posix()}"] = xml
    bad = 0
    loaded = set()
    windows = 0
    for java in sorted(repo.rglob("src/**/*Window.java")):
        text = java.read_text(encoding="utf-8")
        found = LAYOUT.findall(text)
        if not found:
            continue
        windows += 1
        for name in found:
            loaded.add(name)
            xml = layouts.get(name)
            if xml is None:
                print(f"  !! {java.name}: loads {name}, which does not exist")
                bad += 1
                continue
            ids = set(re.findall(r'\bid="([A-Za-z0-9_]+)"', xml.read_text(encoding="utf-8")))
            for wanted in sorted(set(ASKED.findall(text))):
                if wanted not in ids:
                    print(f"  !! {java.name}: asks for pane '{wanted}', which {name} does not have")
                    bad += 1
    for name, xml in sorted(layouts.items()):
        try:
            ET.parse(xml)
        except ET.ParseError as problem:
            print(f"  !! {name}: not well-formed XML ({problem})")
            bad += 1
        if name not in loaded:
            print(f"  !! {name}: no window loads this layout")
            bad += 1
    print(f"  {windows} window class(es), {len(layouts)} layout(s)")
    return bad


if __name__ == "__main__":
    here = pathlib.Path(__file__).resolve().parent.parent
    print("windows:")
    failures = sum(check(r) for r in (sys.argv[1:] or [here]))
    print("  every window finds its panes" if failures == 0 else f"  {failures} problem(s)")
    sys.exit(1 if failures else 0)
