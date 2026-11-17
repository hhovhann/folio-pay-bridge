#!/usr/bin/env python3
"""Consistency check.

1. Every phase folder has requirements.md, plan.md and validation.md.
2. A phase marked done has every box ticked (boxes marked "(manual)" may stay open).
3. Every ticked box that names a test as `Class.method` points at a test that exists and ran without failure in
   target/surefire-reports. Run ./mvnw test first; with --no-reports this third check is skipped.
"""
import pathlib, re, sys
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parent.parent
specs = root / "specs"
errors = []


def ran_tests():
    """Map 'SimpleClassName.method' to True (passed) or False (failed/errored/skipped) from the surefire XML reports."""
    results = {}
    for xml in (root / "target" / "surefire-reports").glob("TEST-*.xml"):
        for case in ET.parse(xml).getroot().iter("testcase"):
            simple = case.get("classname", "").rsplit(".", 1)[-1]
            bad = any(child.tag in ("failure", "error", "skipped") for child in case)
            results[f"{simple}.{case.get('name').split('(')[0]}"] = not bad
    return results


roadmap = (specs / "roadmap.md").read_text(encoding="utf-8")
sections = re.split(r"^## ", roadmap, flags=re.M)[1:]
done = [s.splitlines()[0] for s in sections if re.search(r"^Status: done \d{4}-\d{2}-\d{2}$", s, flags=re.M)]
phases = sorted(p for p in specs.iterdir() if p.is_dir())
for p in phases:
    for f in ("requirements.md", "plan.md", "validation.md"):
        if not (p / f).is_file():
            errors.append(f"{p.name}: missing {f}")

for i, title in enumerate(done):
    m = re.match(r"Phase (\d+)", title)
    if not m:
        continue
    n = m.group(1)
    folder = [p for p in phases if re.match(rf"\d{{4}}-\d{{2}}-\d{{2}}-p{n}-", p.name)]
    if not folder:
        errors.append(f"{title}: marked done but no folder p{n}-*")
        continue
    val = (folder[0] / "validation.md").read_text(encoding="utf-8")
    open_boxes = re.findall(r"^- \[ \] .*", val, flags=re.M)
    manual = [b for b in open_boxes if "(manual)" in b]
    if len(open_boxes) != len(manual):
        errors.append(f"{title}: {len(open_boxes) - len(manual)} unticked box(es) in {folder[0].name}/validation.md")

checked = 0
if "--no-reports" not in sys.argv:
    reports = ran_tests()
    if not reports:
        errors.append("no surefire reports found: run ./mvnw test first (or pass --no-reports)")
    for p in phases:
        for line in (p / "validation.md").read_text(encoding="utf-8").splitlines():
            if not line.startswith("- [x]"):
                continue
            for ref in re.findall(r"`([A-Z][A-Za-z0-9]*\.[a-z][A-Za-z0-9]*)", line):
                checked += 1
                if ref not in reports:
                    errors.append(f"{p.name}: ticked test {ref} was not found in the test reports")
                elif not reports[ref]:
                    errors.append(f"{p.name}: ticked test {ref} did not pass")
for e in errors:
    print("ERROR", e)
print(f"{len(phases)} phase folder(s), {len(done)} done, {checked} ticked test reference(s) checked, {len(errors)} error(s)")
sys.exit(1 if errors else 0)
