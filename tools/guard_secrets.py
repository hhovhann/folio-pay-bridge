#!/usr/bin/env python3
"""Guardrail: no card number and no credential may enter this repository, whether an engineer or an AI agent writes it.

Three ways to run it:
  python3 tools/guard_secrets.py --repo        scan every tracked file (CI fails the build on a finding)
  python3 tools/guard_secrets.py --hook        Claude Code PreToolUse hook: reads the tool call as JSON on stdin and
                                               blocks a Write/Edit/Bash that would put a card number or secret on disk
  python3 tools/guard_secrets.py FILE...       scan the named files

A card number is a Luhn-valid run of 13 to 19 digits (spaces or dashes allowed between groups) that starts like a card
(2-6) and is not one of the public test numbers below. Findings are printed masked: the guard must not leak what it found.
Standard library only, so it runs before any build.
"""
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
SKIP_SUFFIXES = (".png", ".jpg", ".mp4", ".jar", ".class", ".lock", ".ico", ".woff", ".woff2")
SKIP_FILES = {"tools/guard_secrets.py"}  # holds the allowlist itself


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
        if path.endswith(SKIP_SUFFIXES) or path in SKIP_FILES:
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
    path = data.get("file_path", "")
    if path.endswith("tools/guard_secrets.py"):
        return 0
    texts = [data.get(k, "") for k in ("content", "new_string", "command", "new_source")]
    texts += [e.get("new_string", "") for e in data.get("edits", []) or []]
    found = findings("\n".join(t for t in texts if isinstance(t, str)))
    if not found:
        return 0
    # Exit code 2 blocks the tool call; stderr goes back to the agent as the reason.
    print(f"Blocked by tools/guard_secrets.py ({tool}): {', '.join(found)}. Use a public test card number (see the allowlist) "
          "and never write credentials into files or commands (CLAUDE.md rules 1 and 5).", file=sys.stderr)
    return 2


def main(argv):
    if argv == ["--hook"]:
        return hook()
    if argv == ["--repo"]:
        files = subprocess.run(["git", "ls-files", "--cached", "--others", "--exclude-standard"], capture_output=True, text=True, check=True).stdout.split()
        problems = scan_files(files)
        print(f"guard_secrets: {len(files)} files scanned (tracked and new), {problems} finding(s)")
        return 1 if problems else 0
    return 1 if scan_files(argv) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
