"""Does every pane a window looks for exist in its BlockUI layout, and does every layout belong to a window?

A window class finds its controls by id, `findPaneOfTypeByID("lines", ScrollingList.class)`, and BlockUI answers
null for an id that is not in the xml. The code then skips the control without a word (the window opens, empty).
Nothing at build time says so. This reads both sides: for every client window class that loads a layout from
`gui/<name>.xml`, each id it asks for has to be an `id="..."` in that file, and the file must be well-formed XML.
The other direction is checked too: an id in the xml that nothing asks for is only noted (a layout may hold
decoration), but a layout file no window loads is an error.

Rows that a window fills itself (ROW_RULES): BlockUI's scrolling list keeps 8 px of its width for the scrollbar and
clips what lies under it, and a text pane wraps by its own rules unless told not to. So the row's text pane must end
inside the list's width less 8, it must not wrap, and the window's row width in font units (the width it cuts lines
to) must stay 2 units under the pane's width divided by its text scale (S10b: the Decorator's calendar page lost the
last letter of a full row).

Run: python -I tools/check_windows.py [repo ...]   (exit 1 on a failure)
"""
import pathlib
import math
import re
import sys
import xml.etree.ElementTree as ET
from fractions import Fraction

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


# (layout, list id, row text pane id, window class file, its row width constant)
ROW_RULES = [("gui/decorator_info.xml", "lines", "line", "DecoratorInfoWindow.java", "ROW_WIDTH")]
SCROLLBAR = 8


def _pair(text):
    a, b = text.split()
    return float(a.rstrip("%")), float(b.rstrip("%"))


def check_rows(repo):
    repo = pathlib.Path(repo)
    bad = 0
    for layout, list_id, pane_id, window, constant in ROW_RULES:
        xmls = [x for gui in repo.glob("resources/assets/*/gui") for x in [gui / layout.removeprefix("gui/")] if x.exists()]
        javas = list(repo.rglob(f"src/**/{window}"))
        if not xmls or not javas:
            print(f"  !! rows: {layout} or {window} not found")
            bad += 1
            continue
        root = ET.parse(xmls[0]).getroot()
        lst = next((e for e in root.iter("list") if e.get("id") == list_id), None)
        pane = next((e for e in lst.iter("text") if e.get("id") == pane_id), None) if lst is not None else None
        match = re.search(r"static\s+final\s+int\s+" + constant + r"\s*=\s*(\d+)\s*;", javas[0].read_text(encoding="utf-8"))
        if lst is None or pane is None or match is None:
            print(f"  !! rows: {layout} list '{list_id}' with text '{pane_id}', or {window}.{constant}, not found")
            bad += 1
            continue
        list_w = _pair(lst.get("size"))[0]
        x = _pair(pane.get("pos", "0 0"))[0]
        w = _pair(pane.get("size"))[0]
        scale = Fraction(pane.get("textscale", "1"))
        row = int(match.group(1))
        shown = list_w - SCROLLBAR
        if x + w > shown:
            print(f"  !! rows: {layout} '{pane_id}' ends at {x + w:g} px, the list shows {shown:g} px (its {list_w:g} less the scrollbar)")
            bad += 1
        fit = math.floor(Fraction(pane.get("size").split()[0]) / scale) - 2  # exact: 156 / 0.8 is 195, not 194.99...
        if row > fit:
            print(f"  !! rows: {window.removesuffix('.java')}.{constant} = {row} font units, the {w:g} px pane at scale {float(scale):g} holds at most {fit}")
            bad += 1
        if pane.get("wrap", "").lower() != "false":
            print(f"  !! rows: {layout} '{pane_id}' wraps (BlockUI's default); its rows are already one line: set wrap=\"false\"")
            bad += 1
        print(f"  rows of {layout}: pane {x:g}+{w:g} px of {shown:g}, {constant} {row} of {fit} font units")
    return bad


if __name__ == "__main__":
    here = pathlib.Path(__file__).resolve().parent.parent
    print("windows:")
    failures = sum(check(r) + check_rows(r) for r in (sys.argv[1:] or [here]))
    print("  every window finds its panes" if failures == 0 else f"  {failures} problem(s)")
    sys.exit(1 if failures else 0)
