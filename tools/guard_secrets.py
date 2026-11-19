#!/usr/bin/env python3
"""Guardrail: no card number and no credential may enter this repository, whether an engineer or an AI agent writes it.

Three ways to run it:
  python3 tools/guard_secrets.py --repo        scan every tracked file (CI fails the build on a finding)
  python3 tools/guard_secrets.py --hook        Claude Code PreToolUse hook: reads the tool call as JSON on stdin and
                                               blocks a Write/Edit/Bash that would put a card number or secret on disk
  python3 tools/guard_secrets.py --self-test   what it must find and what it must let through (CI runs it)
  python3 tools/guard_secrets.py FILE...       scan the named files

A card number is a Luhn-valid run of 13 to 19 digits (spaces or dashes allowed between groups) that starts like a card
(2-6) and is not one of the public test numbers below. Findings are printed masked: the guard must not leak what it found.
Standard library only, so it runs before any build.
"""
import contextlib
import io
import json
import re
import subprocess
import sys

# Public test card numbers published by the card schemes and processors for testing. Synthetic data only (CLAUDE.md rule 5).
PUBLIC_TEST_PANS = {
    "4111111111111111", "4242424242424242", "4000056655665556", "4012888888881881", "4000000000000002",
    "4000000000009995", "4000000000000069", "4000000000000119", "4000002500003155", "4000000000003220",
    "5555555555554444", "5105105105105100", "5200828282828210", "2223003122003222", "2222400070000005",
    "378282246310005", "371449635398431", "6011111111111117", "6011000990139424", "3056930009020004",
    "36227206271667", "3566002020360505", "6200000000000005", "4917610000000000", "4000000000000077",
}

SECRETS = [
    ("AWS access key id", re.compile(r"\b(AKIA|ASIA)[0-9A-Z]{16}\b")),
    ("private key", re.compile(r"-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----")),
    ("GitHub token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,}\b")),
    ("Slack token", re.compile(r"\bxox[abpors]-[A-Za-z0-9-]{10,}\b")),
    ("Anthropic API key", re.compile(r"\bsk-ant-[A-Za-z0-9_-]{20,}\b")),
    ("this service's API key", re.compile(r"\bfpb_(live|test)_[A-Za-z0-9]{32,}\b")),
    ("webhook signing secret", re.compile(r"\bwhsec_(?!test_|fullflow_|demo_)[A-Za-z0-9_]{24,}\b")),
    ("CVV field with a value", re.compile(r"""["']?(cvv|cvc|cvv2|cid)["']?\s*[:=]\s*["']?\d{3,4}\b""", re.IGNORECASE)),
]

CANDIDATE = re.compile(r"(?<![\dA-Za-z])[2-6]\d(?:[ -]?\d){11,17}(?![\dA-Za-z])")
SKIP_SUFFIXES = (".png", ".jpg", ".gif", ".mp4", ".jar", ".class", ".lock", ".ico", ".woff", ".woff2")
# This file is scanned like any other (final code review). Its allowlist passes because it is the allowlist; an edit that adds a number
# to it is checked against the allowlist as it was before the edit, so an agent cannot allowlist a real card number on its own.


def luhn(digits):
    total = 0
    for i, ch in enumerate(reversed(digits)):
        d = int(ch)
        if i % 2:
            d = d * 2 - 9 if d > 4 else d * 2
        total += d
    return total % 10 == 0


def findings(text):
    found = []
    for match in CANDIDATE.finditer(text):
        digits = re.sub(r"\D", "", match.group())
        if 13 <= len(digits) <= 19 and luhn(digits) and digits not in PUBLIC_TEST_PANS:
            found.append("card number " + digits[:1] + "*" * (len(digits) - 5) + digits[-4:])
    for name, pattern in SECRETS:
        if pattern.search(text):
            found.append(name)
    return found


def scan_files(paths):
    problems = 0
    for path in paths:
        if path.endswith(SKIP_SUFFIXES):
            continue
        try:
            text = open(path, encoding="utf-8", errors="ignore").read()
        except (IsADirectoryError, FileNotFoundError):
            continue
        for f in findings(text):
            print(f"{path}: {f}")
            problems += 1
    return problems


def hook():
    call = json.load(sys.stdin)
    tool = call.get("tool_name", "")
    data = call.get("tool_input", {}) or {}
    texts = [data.get(k, "") for k in ("content", "new_string", "command", "new_source")]
    texts += [e.get("new_string", "") for e in data.get("edits", []) or []]
    found = findings("\n".join(t for t in texts if isinstance(t, str)))
    if not found:
        return 0
    # Exit code 2 blocks the tool call; stderr goes back to the agent as the reason.
    print(f"Blocked by tools/guard_secrets.py ({tool}): {', '.join(found)}. Use a public test card number (see the allowlist) "
          "and never write credentials into files or commands (CLAUDE.md rules 1 and 5).", file=sys.stderr)
    return 2


def repo_files(listing):
    """Paths from `git ls-files -z`: NUL-separated, so a name with a space or a non-ASCII character is one path, read as it is."""
    return [p for p in listing.decode("utf-8", "surrogateescape").split("\0") if p]


def with_check_digit(body):
    """A Luhn-valid number from its digits without the last: lets the self-test make a card number without writing one here."""
    return next(body + d for d in "0123456789" if luhn(body + d))


def self_test():
    """What the guard must find and what it must let through, the way guard_commands.py has its table (standards section 12)."""
    made_up = with_check_digit("49" + "7" * 13)
    hook_input = {"tool_name": "Edit", "tool_input": {"file_path": "tools/guard_secrets.py", "new_string": f'    "{made_up}",'}}
    sys.stdin, saved = io.StringIO(json.dumps(hook_input)), sys.stdin
    try:
        with contextlib.redirect_stderr(io.StringIO()):
            allowlist_edit_blocked = hook() == 2
    finally:
        sys.stdin = saved
    checks = {
        "a card number that is not a public test number is found": bool(findings(f"pan={made_up}")),
        "a public test number and ordinary text pass": not findings("4111111111111111 and a total of 1000 for room 2001"),
        "this file, allowlist included, scans clean": not findings(open(__file__, encoding="utf-8").read()),
        "adding a number to the allowlist through an agent's edit is refused": allowlist_edit_blocked,
        "a path with a space is one path": repo_files(b"docs/a b.csv\0README.md\0") == ["docs/a b.csv", "README.md"],
    }
    for name, ok in checks.items():
        if not ok:
            print("WRONG:", name)
    wrong = sum(1 for ok in checks.values() if not ok)
    print(f"guard_secrets: {len(checks)} checks, {wrong} wrong")
    return 1 if wrong else 0


def main(argv):
    if argv == ["--hook"]:
        return hook()
    if argv == ["--self-test"]:
        return self_test()
    if argv == ["--repo"]:
        listing = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], capture_output=True, check=True).stdout
        files = repo_files(listing)
        problems = scan_files(files)
        print(f"guard_secrets: {len(files)} files scanned (tracked and new), {problems} finding(s)")
        return 1 if problems else 0
    return 1 if scan_files(argv) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
