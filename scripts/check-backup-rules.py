#!/usr/bin/env python3
"""Check the Android backup rule files against docs/ANDROID.md section 13.4.

That section states the rule as a release blocker rather than a preference:

    release MUST fail when either file names a path under `vaults/` or
    `registry/`, or declares `<include domain="root">` or
    `<include domain="external">`

and adds that the exclusion must be proved by an actual backup and restore run
rather than by reading the XML. This script is the reading half. It is the half
that can run without a device, and the half that catches the change nobody
meant to make: an `<include>` set makes everything else excluded, so widening
one is how vault storage would enter an archive, and it would enter silently.

The script also checks the other direction. docs/product/DISCREET_MODE.md
requires public-shell storage to be *included*, so a rules file that stopped
naming `public/` would break the shell's usefulness just as quietly. A missing
include is not a security failure and is a product one, and both are checked
here because both are consequences of the same two files.

It runs offline, reads only the checked-in XML, and prints what it found.
"""

from __future__ import annotations

import sys
from xml.etree import ElementTree
from pathlib import Path

# Every path fragment that names vault storage. docs/ARCHITECTURE.md section
# 14.4 fixes the layout, and these are the directories that hold ciphertext,
# descriptors, and plaintext scratch.
FORBIDDEN_PATHS = ("vaults", "registry", "incoming", "quarantine", "scratch", "chur")

# Domains that reach outside the application's own files, whatever path they
# name. Section 13.4 names both.
FORBIDDEN_DOMAINS = ("root", "external")

# What the public shell needs, from DISCREET_MODE.md.
REQUIRED = {("file", "public/"), ("sharedpref", "public_shell.xml")}

RULE_FILES = (
    "apps/androidApp/src/main/res/xml/data_extraction_rules.xml",
    "apps/androidApp/src/main/res/xml/backup_rules.xml",
)


def check(path: Path) -> list[str]:
    """Return the failures in one rule file."""
    if not path.exists():
        return [f"{path}: the file is absent, and the manifest names it"]

    failures: list[str] = []
    root = ElementTree.parse(path).getroot()
    seen: set[tuple[str, str]] = set()

    for element in root.iter():
        if element.tag not in ("include", "exclude"):
            continue
        domain = element.get("domain", "")
        rule_path = element.get("path", "")
        where = f"{path}: <{element.tag} domain=\"{domain}\" path=\"{rule_path}\">"

        if domain in FORBIDDEN_DOMAINS:
            failures.append(f"{where} reaches outside the application's files")
            continue

        if element.tag == "include":
            seen.add((domain, rule_path))
            fragments = [part for part in rule_path.split("/") if part]
            for fragment in fragments:
                if fragment in FORBIDDEN_PATHS:
                    failures.append(f"{where} names vault storage")
                    break

    missing = REQUIRED - seen
    for domain, rule_path in sorted(missing):
        failures.append(
            f'{path}: no <include domain="{domain}" path="{rule_path}">, '
            "so public-shell content would not survive a device transfer"
        )
    return failures


def main() -> int:
    repository = Path(__file__).resolve().parent.parent
    failures: list[str] = []
    for name in RULE_FILES:
        failures.extend(check(repository / name))

    if failures:
        print("backup rules: FAILED", file=sys.stderr)
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        return 1

    print(f"backup rules: {len(RULE_FILES)} file(s) checked, no vault path included")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
