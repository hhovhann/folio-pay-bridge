#!/usr/bin/env python3
"""Refresh the OHIP contract the OPERA adapter is tested against (D-42).

Takes Oracle's published OHIP specifications (github.com/oracle/hospitality-api-docs, Universal Permissive License) at one commit and keeps
only the operations the bridge calls, with every definition, parameter and response they reference, so the test resources stay small and
reviewable. Nothing in a kept operation is changed.

    python3 tools/ohip_specs.py <commit>                  # download that commit from GitHub
    python3 tools/ohip_specs.py <commit> --from DIR       # use files already downloaded into DIR (csh.json, bof.json, publishedoauth.json, LICENSE.txt)

Writes src/test/resources/ohip/. Run OhipContractTest afterwards: a new Oracle version that changed a shape the bridge relies on fails it.
"""
import argparse
import json
import pathlib
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "src/test/resources/ohip"
RAW = "https://raw.githubusercontent.com/oracle/hospitality-api-docs/{commit}/{path}"
SPECS = {  # file -> (path in Oracle's repository, operations the bridge uses)
    "csh.json": ("rest-api-specs/property/v1/csh.json", ["postBillingPayment", "getFolio"]),
    "bof.json": ("rest-api-specs/property/v1/bof.json", ["getBusinessDate"]),
    "publishedoauth.json": ("rest-api-specs/security/v1/publishedoauth.json", ["getToken"]),
}


def load(commit, source, name, path):
    if source:
        return (pathlib.Path(source) / name).read_bytes()
    with urllib.request.urlopen(RAW.format(commit=commit, path=path), timeout=120) as reply:
        return reply.read()


def refs(node):
    """Every local $ref inside a JSON node, as (section, name)."""
    if isinstance(node, dict):
        ref = node.get("$ref")
        if isinstance(ref, str) and ref.startswith("#/"):
            _, section, name = ref.split("/", 2)
            yield section, name
        for value in node.values():
            yield from refs(value)
    elif isinstance(node, list):
        for value in node:
            yield from refs(value)


def trim(spec, operations):
    kept_paths = {}
    for path, item in spec["paths"].items():
        methods = {m: op for m, op in item.items() if isinstance(op, dict) and op.get("operationId") in operations}
        if methods:
            shared = {k: v for k, v in item.items() if k == "parameters"}
            kept_paths[path] = {**shared, **methods}
    found = {op["operationId"] for item in kept_paths.values() for op in item.values() if isinstance(op, dict) and "operationId" in op}
    missing = set(operations) - found
    if missing:
        raise SystemExit(f"Oracle's spec no longer has {sorted(missing)}: the adapter needs a look before this contract is refreshed")
    out = {k: v for k, v in spec.items() if k not in ("paths", "definitions", "parameters", "responses", "tags")}
    out["paths"] = kept_paths
    keep = {"definitions": set(), "parameters": set(), "responses": set()}
    todo = list(refs(kept_paths))
    while todo:
        section, name = todo.pop()
        if name in keep[section]:
            continue
        keep[section].add(name)
        todo.extend(refs(spec[section][name]))
    for section, names in keep.items():
        if names:
            out[section] = {name: spec[section][name] for name in sorted(names)}
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("commit", help="the commit of oracle/hospitality-api-docs to pin")
    parser.add_argument("--from", dest="source", help="a directory holding the downloaded files instead of fetching them")
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    for name, (path, operations) in SPECS.items():
        spec = json.loads(load(args.commit, args.source, name, path))
        trimmed = trim(spec, operations)
        (OUT / name).write_text(json.dumps(trimmed, indent=1, sort_keys=False) + "\n")
        print(f"{name}: {', '.join(operations)} ({len(trimmed.get('definitions', {}))} definitions) from {spec['info'].get('version')}")
    licence = load(args.commit, args.source, "LICENSE.txt", "LICENSE.txt").decode()
    (OUT / "LICENSE.txt").write_text(licence)
    (OUT / "SOURCE.md").write_text(
        "# OHIP contract (test resources)\n\n"
        "Oracle's published OHIP specifications, trimmed to the operations the bridge calls by `tools/ohip_specs.py`; nothing in a kept "
        "operation is changed. `OhipContractTest` checks every exchange between the OPERA adapter and the simulator against them.\n\n"
        f"- Source: https://github.com/oracle/hospitality-api-docs at commit `{args.commit}`\n"
        f"- Files: {', '.join(f'`{p}`' for p, _ in SPECS.values())}\n"
        f"- Operations: {', '.join(op for _, ops in SPECS.values() for op in ops)}\n"
        "- Licence: Universal Permissive License 1.0, copyright Oracle and/or its affiliates (`LICENSE.txt`, copied as the licence requires)\n")


if __name__ == "__main__":
    main()
