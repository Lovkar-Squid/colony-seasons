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
last letter of a full row). The list's height must be a whole number of rows, so no row sits half cut on its bottom
edge (S10h).

Panes that must stay in view under a list (FOOTER_RULES, S10h: the Calendar page's Discord / Support line sat half cut
at the bottom of the list until the player scrolled): the pane lies below the list and inside the window (no lower
than the list used to reach), it wraps, it holds its lines at its text scale and line space, and it is as wide as a
row, so it wraps the line no tighter than the rows did.

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
ROW_RULES = [("gui/decorator_info.xml", "lines", "line", "DecoratorInfoWindow.java", "ROW_WIDTH"),
             ("gui/town_square_info.xml", "lines", "line", "TownSquareInfoWindow.java", "ROW_WIDTH")]
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
        box = next(lst.iter("box"), None)
        row_h = _pair(box.get("size"))[1] if box is not None else 0
        list_h = _pair(lst.get("size"))[1]
        if row_h <= 0 or list_h % row_h != 0:
            print(f"  !! rows: {layout} list '{list_id}' is {list_h:g} px high, not a whole number of {row_h:g} px rows (the last row is cut)")
            bad += 1
        if pane.get("wrap", "").lower() != "false":
            print(f"  !! rows: {layout} '{pane_id}' wraps (BlockUI's default); its rows are already one line: set wrap=\"false\"")
            bad += 1
        print(f"  rows of {layout}: pane {x:g}+{w:g} px of {shown:g}, {constant} {row} of {fit} font units")
    return bad


# (layout, pane id, the list it sits under, lines it must hold, the lowest y it may reach)
FOOTER_RULES = [("gui/decorator_info.xml", "links", "lines", 3, 236)]
LINE = 9   # the font's line height in font units


def check_footers(repo):
    repo = pathlib.Path(repo)
    bad = 0
    for layout, pane_id, list_id, need, lowest in FOOTER_RULES:
        xmls = [x for gui in repo.glob("resources/assets/*/gui") for x in [gui / layout.removeprefix("gui/")] if x.exists()]
        if not xmls:
            print(f"  !! footer: {layout} not found")
            bad += 1
            continue
        root = ET.parse(xmls[0]).getroot()
        win_w, win_h = _pair(root.get("size"))
        lst = next((e for e in root.iter("list") if e.get("id") == list_id), None)
        pane = next((e for e in root.iter("text") if e.get("id") == pane_id), None)
        if lst is None or pane is None:
            print(f"  !! footer: {layout} list '{list_id}' or text '{pane_id}' not found")
            bad += 1
            continue
        lx, ly = _pair(lst.get("pos", "0 0"))
        lw, lh = _pair(lst.get("size"))
        px, py = _pair(pane.get("pos", "0 0"))
        pw, ph = _pair(pane.get("size"))
        scale = Fraction(pane.get("textscale", "1"))
        space = int(pane.get("linespace", "0"))
        # BlockUI's own rule (AbstractTextElement.recalcPreparedTextBox): (int) (height / scale) + 1 font units, cut
        # into lines of LINE + linespace; a height of 25 at scale 0.8 and line space 2 holds 2 lines, not 3
        lines = (math.floor(Fraction(pane.get("size").split()[1]) / scale) + 1) // (LINE + space)
        row = next((e for e in lst.iter("text")), None)
        row_w = _pair(row.get("size"))[0] if row is not None else pw
        problems = []
        if py < ly + lh:
            problems.append(f"starts at y {py:g}, inside the list (which ends at {ly + lh:g})")
        if py + ph > min(lowest, win_h) or px + pw > win_w or px < 0:
            problems.append(f"reaches {px + pw:g},{py + ph:g}, past the window's {win_w:g} x {min(lowest, win_h):g}")
        if pane.get("wrap", "").lower() != "true":
            problems.append("does not wrap")
        if lines < need:
            problems.append(f"holds {lines} lines at scale {float(scale):g} and line space {space}, {need} needed")
        if pw < row_w:
            problems.append(f"is {pw:g} px wide, narrower than a row ({row_w:g} px)")
        for problem in problems:
            print(f"  !! footer: {layout} '{pane_id}' {problem}")
        bad += len(problems)
        print(f"  footer of {layout}: '{pane_id}' at y {py:g}-{py + ph:g} under the list (ends {ly + lh:g}), {lines} lines")
    return bad


if __name__ == "__main__":
    here = pathlib.Path(__file__).resolve().parent.parent
    print("windows:")
    failures = sum(check(r) + check_rows(r) + check_footers(r) for r in (sys.argv[1:] or [here]))
    print("  every window finds its panes" if failures == 0 else f"  {failures} problem(s)")
    sys.exit(1 if failures else 0)
