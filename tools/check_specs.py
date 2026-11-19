#!/usr/bin/env python3
"""Consistency check.

1. Every phase folder has requirements.md, plan.md and validation.md.
2. A phase marked done has every box ticked (boxes marked "(manual)" may stay open).
3. Every ticked box that names a test as `Class.method` points at a test that exists and ran without failure in
   target/surefire-reports. Run ./mvnw test first; with --no-reports this third check is skipped.
4. Every entry in docs/agent-log.md is cited, as "(log N)", by a rule in docs/engineering-standards.md: a mistake is
   not finished until it has become a rule or a test, so the next agent does not make it again.
"""
import pathlib, re, sys
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parent.parent
specs = root / "specs"
errors = []


def ran_tests():
    """Map 'SimpleClassName.method' to True (passed) or False (failed/errored/skipped) from the surefire XML reports.

    The class comes from the test suite's name, the class's full name: a test case's classname is the class's @DisplayName when it has one.
    One name can run more than once (a parameterized test, two classes with one simple name): it passed only if every run passed (final
    code review: the last run used to overwrite the others, so a failing first case could read as passed).
    """
    results = {}
    for xml in (root / "target" / "surefire-reports").glob("TEST-*.xml"):
        suite = ET.parse(xml).getroot()
        simple = suite.get("name", "").rsplit(".", 1)[-1]
        for case in suite.iter("testcase"):
            bad = any(child.tag in ("failure", "error", "skipped") for child in case)
            key = f"{simple}.{case.get('name').split('(')[0]}"
            results[key] = results.get(key, True) and not bad
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
    # docs/edge-cases.md promises a test for every case it lists: each test it names must exist and have passed
    edge = root / "docs" / "edge-cases.md"
    if edge.is_file():
        names = set()
        for ref in re.findall(r"`([A-Z][A-Za-z0-9]*\.[A-Za-z0-9*]+)`", edge.read_text(encoding="utf-8")):
            if ref.endswith(".*"):
                runs = [passed for k, passed in reports.items() if k.startswith(ref[:-1])]
                if not runs:
                    errors.append(f"docs/edge-cases.md: no tests found for {ref}")
                elif not all(runs):
                    errors.append(f"docs/edge-cases.md: a test of {ref} did not pass")
                continue
            names.add(ref)
        for ref in sorted(names):
            checked += 1
            if ref not in reports:
                errors.append(f"docs/edge-cases.md: test {ref} was not found in the test reports")
            elif not reports[ref]:
                errors.append(f"docs/edge-cases.md: test {ref} did not pass")
# Every lesson in the agent log has become a rule in the engineering standards (and every citation points at a real entry).
standards, log = root / "docs" / "engineering-standards.md", root / "docs" / "agent-log.md"
if standards.is_file() and log.is_file():
    cited = set()
    for group in re.findall(r"\(log ([\d, ]+)\)", standards.read_text(encoding="utf-8")):
        cited.update(int(n) for n in re.findall(r"\d+", group))
    entries = [int(n) for n in re.findall(r"^(\d+)\. \*\*", log.read_text(encoding="utf-8"), flags=re.M)]
    for n in entries:
        if n not in cited:
            errors.append(f"docs/agent-log.md entry {n} is not cited by any rule in docs/engineering-standards.md: turn the lesson into a rule or a test")
    for n in sorted(cited - set(entries)):
        errors.append(f"docs/engineering-standards.md cites agent-log entry {n}, which does not exist")
else:
    errors.append("docs/engineering-standards.md and docs/agent-log.md must both exist")
for e in errors:
    print("ERROR", e)
print(f"{len(phases)} phase folder(s), {len(done)} done, {checked} ticked test reference(s) checked, {len(errors)} error(s)")
sys.exit(1 if errors else 0)
