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

With --merged-manifest it also checks the manifest that a build merged, so a
library or variant manifest cannot replace either attribute; CI runs this mode
after it assembles the debug build.

Each section is checked on its own. A <cloud-backup> or <device-transfer>
section that is absent or has no <include> backs up every file, so one
correct section does not cover for the other. The manifest must also name both
files, because a rule file that the manifest does not name has no effect.

It runs offline, reads only the checked-in XML, and prints what it found.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from xml.etree import ElementTree

# Every path fragment that names vault storage. docs/ARCHITECTURE.md section
# 14.4 fixes the layout, and these are the directories that hold ciphertext,
# descriptors, and plaintext scratch.
FORBIDDEN_PATHS = ("vaults", "registry", "incoming", "quarantine", "scratch", "chur")

# Domains that reach outside the application's own files, whatever path they
# name. Section 13.4 names both; device_root is the same reach in
# device-protected storage.
FORBIDDEN_DOMAINS = ("root", "external", "device_root")

# Chur keeps nothing for a backup in device-protected storage, so no
# device_* domain may be included at all.
DEVICE_DOMAIN_PREFIX = "device_"

# What the public shell needs, from DISCREET_MODE.md.
REQUIRED = {("file", "public/"), ("sharedpref", "public_shell.xml")}

# Each rule file, its root element, and the sections that must each hold the
# required includes. A data-extraction section that is absent, or that has no
# <include>, backs up every file of the application, so each one is checked
# on its own rather than the file as a whole.
RULE_FILES = {
    "apps/androidApp/src/main/res/xml/data_extraction_rules.xml":
        ("data-extraction-rules", ("cloud-backup", "device-transfer")),
    "apps/androidApp/src/main/res/xml/backup_rules.xml":
        ("full-backup-content", (None,)),
}

MANIFEST = "apps/androidApp/src/main/AndroidManifest.xml"
ANDROID = "{http://schemas.android.com/apk/res/android}"
# The manifest attributes that make the platform read the two files above.
WIRING = {
    "dataExtractionRules": "@xml/data_extraction_rules",
    "fullBackupContent": "@xml/backup_rules",
}


def check_rule(path: Path, element: ElementTree.Element) -> tuple[list[str], tuple[str, str] | None]:
    """Return the failures of one <include> or <exclude>, and what it includes."""
    domain = element.get("domain", "")
    rule_path = element.get("path", "")
    where = f'{path}: <{element.tag} domain="{domain}" path="{rule_path}">'

    if element.tag != "include":
        return [], None
    if domain in FORBIDDEN_DOMAINS:
        return [f"{where} reaches outside the application's files"], None
    if domain.startswith(DEVICE_DOMAIN_PREFIX):
        return [f"{where} includes device-protected storage"], None
    # "." names the same directory, so "./." and "public/./" normalize like
    # the platform does. An include whose normalized path is empty names no
    # directory of its own and therefore covers every file of the domain,
    # which is how vault storage enters an archive without any fragment of
    # its path appearing in the rule. Section 13.4 makes that a release
    # blocker by the same reasoning as a literal vault path. ".." leaves the
    # named directory, so it is refused anywhere in the path.
    fragments = [part for part in rule_path.split("/") if part and part != "."]
    if ".." in fragments:
        return [f"{where} leaves its directory with .."], None
    if not fragments:
        return [f"{where} includes every file of the domain"], None
    for fragment in fragments:
        if fragment in FORBIDDEN_PATHS:
            return [f"{where} names vault storage"], None
    return [], (domain, rule_path)


def check_section(path: Path, name: str, section: ElementTree.Element) -> list[str]:
    """Return the failures of one section: its rules, and its required includes."""
    failures: list[str] = []
    seen: set[tuple[str, str]] = set()
    includes = 0
    for element in section:
        # Only plain <include> and <exclude> may appear. A namespaced or
        # unknown element is one this check cannot read, and the platform
        # parser may read it differently, so it fails rather than being
        # skipped.
        if element.tag not in ("include", "exclude"):
            failures.append(f"{path}: <{element.tag}> in <{name}> is not a rule this check knows")
            continue
        if len(element):
            failures.append(f"{path}: <{element.tag}> in <{name}> has child elements")
        includes += element.tag == "include"
        problems, included = check_rule(path, element)
        failures.extend(problems)
        if included is not None:
            seen.add(included)
    if includes == 0:
        failures.append(f"{path}: <{name}> has no <include>, so it backs up every file")
    for domain, rule_path in sorted(REQUIRED - seen):
        failures.append(
            f'{path}: <{name}> has no <include domain="{domain}" path="{rule_path}">, '
            "so public-shell content would not survive a device transfer"
        )
    return failures


def check(path: Path, root_tag: str, sections: tuple[str | None, ...]) -> list[str]:
    """Return the failures in one rule file."""
    if not path.exists():
        return [f"{path}: the file is absent, and the manifest names it"]

    root = ElementTree.parse(path).getroot()
    if root.tag != root_tag:
        return [f"{path}: the root element is <{root.tag}>, not <{root_tag}>"]

    failures: list[str] = []
    for name in sections:
        if name is None:
            failures.extend(check_section(path, root_tag, root))
            continue
        found = root.findall(name)
        if len(found) != 1:
            failures.append(f"{path}: expected one <{name}>, found {len(found)}")
            continue
        failures.extend(check_section(path, name, found[0]))
    known = {name for name in sections if name is not None}
    for child in root:
        if known and child.tag not in known:
            failures.append(f"{path}: <{child.tag}> is a section this check does not know")
    for element in root.iter():
        if element.tag.startswith("{"):
            failures.append(f"{path}: <{element.tag}> is a namespaced element")
    return failures


def check_manifest(path: Path) -> list[str]:
    """Return the failures in the manifest wiring of the two rule files."""
    if not path.exists():
        return [f"{path}: the manifest is absent"]
    application = ElementTree.parse(path).getroot().find("application")
    if application is None:
        return [f"{path}: no <application>"]
    failures: list[str] = []
    for attribute, expected in WIRING.items():
        actual = application.get(ANDROID + attribute)
        if actual != expected:
            failures.append(f'{path}: android:{attribute} is "{actual}", not "{expected}"')
    return failures


# Where a debug build writes the merged manifest. Library and variant manifests
# are merged into it, so it is what the platform actually reads.
MERGED_MANIFESTS = (
    "apps/androidApp/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml",
    "apps/androidApp/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml",
)


def find_merged_manifest(repository: Path) -> Path | None:
    """Return the merged manifest of the last debug build, if one exists."""
    for name in MERGED_MANIFESTS:
        if (repository / name).exists():
            return repository / name
    return None


def run(repository: Path) -> list[str]:
    """Return every failure of the repository at `repository`."""
    failures: list[str] = []
    for name, (root_tag, sections) in RULE_FILES.items():
        failures.extend(check(repository / name, root_tag, sections))
    failures.extend(check_manifest(repository / MANIFEST))
    return failures


def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Check the Android backup rule files.")
    parser.add_argument(
        "--merged-manifest", nargs="?", const="", metavar="PATH",
        help="also check the merged manifest of a build; without PATH, find the debug build's",
    )
    options = parser.parse_args(arguments)
    repository = Path(__file__).resolve().parent.parent
    failures = run(repository)
    if options.merged_manifest is not None:
        merged = Path(options.merged_manifest) if options.merged_manifest else find_merged_manifest(repository)
        if merged is None:
            failures.append("no merged manifest: run a debug build first, or pass its path")
        else:
            failures.extend(check_manifest(merged))

    if failures:
        print("backup rules: FAILED", file=sys.stderr)
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        return 1

    print(f"backup rules: {len(RULE_FILES)} file(s) and the manifest checked, no vault path included")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
