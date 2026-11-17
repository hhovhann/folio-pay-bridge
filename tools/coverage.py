#!/usr/bin/env python3
"""Print line and branch coverage from the JaCoCo report (run ./mvnw test first), worst packages and classes first.

  python3 tools/coverage.py            summary and every class below 100% line coverage
  python3 tools/coverage.py --min 90   also exit 1 if total line coverage is under 90%
"""
import csv
import pathlib
import sys

report = pathlib.Path(__file__).resolve().parent.parent / "target/site/jacoco/jacoco.csv"
if not report.is_file():
    sys.exit("No JaCoCo report: run ./mvnw test first")

rows = list(csv.DictReader(report.open()))
packages = {}
classes = []
for r in rows:
    li, lc = int(r["LINE_MISSED"]), int(r["LINE_COVERED"])
    bi, bc = int(r["BRANCH_MISSED"]), int(r["BRANCH_COVERED"])
    p = packages.setdefault(r["PACKAGE"], [0, 0, 0, 0])
    p[0] += li; p[1] += lc; p[2] += bi; p[3] += bc
    classes.append((r["PACKAGE"] + "." + r["CLASS"], li, lc, bi, bc))


def pct(missed, covered):
    total = missed + covered
    return 100.0 if total == 0 else 100.0 * covered / total


tl = [sum(p[i] for p in packages.values()) for i in range(4)]
print(f"TOTAL  lines {pct(tl[0], tl[1]):6.2f}% ({tl[1]}/{tl[0] + tl[1]})   branches {pct(tl[2], tl[3]):6.2f}% ({tl[3]}/{tl[2] + tl[3]})\n")
print("by package:")
for name, p in sorted(packages.items(), key=lambda kv: pct(kv[1][0], kv[1][1])):
    print(f"  {pct(p[0], p[1]):6.2f}% lines  {pct(p[2], p[3]):6.2f}% branches  {name}")
print("\nclasses below 100% line coverage (missed lines):")
for name, li, lc, bi, bc in sorted(classes, key=lambda c: -c[1]):
    if li:
        print(f"  {li:4d} missed  {pct(li, lc):6.2f}%  {name}")

minimum = float(sys.argv[sys.argv.index("--min") + 1]) if "--min" in sys.argv else None
if minimum is not None and pct(tl[0], tl[1]) < minimum:
    sys.exit(f"\nline coverage {pct(tl[0], tl[1]):.2f}% is under {minimum}%")
