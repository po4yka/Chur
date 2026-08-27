#!/usr/bin/env python3
"""Verify the vendored agent skills against `skills-lock.json`.

`docs/DEPENDENCY_POLICY.md` "Vendored agent skills" requires every entry to
record the upstream repository, the path within it, a content hash, and the
upstream commit the content was taken from, and says a content hash alone
proves integrity but not provenance.

This script checks the integrity half offline and, with ``--verify-upstream``,
the provenance half against a clone.

The hash it computes, ``contentHash``, covers the whole vendored directory:

    SHA-256 over every file under the skill directory, in ascending order of
    its path relative to that directory, feeding for each file the relative
    path as UTF-8 with ``/`` separators, then the file bytes.

That is not the same value as ``computedHash``, which the external skill
synchronisation tool writes and which does not cover a skill's ``references/``
files. Both are kept: ``computedHash`` belongs to that tool, and
``contentHash`` is the value this repository verifies.

Usage:

    scripts/check-vendored-skills.py                  # verify, offline
    scripts/check-vendored-skills.py --write          # record hashes and commits
    scripts/check-vendored-skills.py --verify-upstream CLONE_DIR
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parent.parent
LOCKFILE = REPOSITORY_ROOT / "skills-lock.json"
SKILLS_ROOT = REPOSITORY_ROOT / ".agents" / "skills"


def content_hash(directory: Path) -> str:
    """The documented directory hash of one vendored skill."""
    digest = hashlib.sha256()
    for path in sorted(p for p in directory.rglob("*") if p.is_file()):
        digest.update(path.relative_to(directory).as_posix().encode("utf-8"))
        digest.update(path.read_bytes())
    return digest.hexdigest()


def upstream_hash(clone: Path, commit: str, skill_directory: str) -> str | None:
    """The same hash, computed over a directory in an upstream clone."""
    listing = subprocess.run(
        ["git", "-C", str(clone), "ls-tree", "-r", "--name-only", commit, "--", skill_directory],
        capture_output=True,
        text=True,
        check=False,
    )
    paths = [line for line in listing.stdout.split("\n") if line]
    if not paths:
        return None
    digest = hashlib.sha256()
    for path in sorted(paths):
        blob = subprocess.run(
            ["git", "-C", str(clone), "show", f"{commit}:{path}"],
            capture_output=True,
            check=False,
        ).stdout
        relative = path[len(skill_directory) :].lstrip("/")
        digest.update(relative.encode("utf-8"))
        digest.update(blob)
    return digest.hexdigest()


def find_commit(clone: Path, skill_directory: str, wanted: str) -> str | None:
    """The newest upstream commit whose directory content hashes to `wanted`."""
    history = subprocess.run(
        ["git", "-C", str(clone), "log", "--format=%H", "--", skill_directory],
        capture_output=True,
        text=True,
        check=False,
    ).stdout.split()
    for commit in history:
        if upstream_hash(clone, commit, skill_directory) == wanted:
            return commit
    return None


def load() -> dict:
    return json.loads(LOCKFILE.read_text(encoding="utf-8"))


def save(lock: dict) -> None:
    LOCKFILE.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")


def verify(lock: dict) -> int:
    failures = 0
    skills = lock["skills"]
    for name, entry in sorted(skills.items()):
        directory = SKILLS_ROOT / name
        if not directory.is_dir():
            print(f"  missing   {name}: no vendored directory")
            failures += 1
            continue
        recorded = entry.get("contentHash")
        if recorded is None:
            print(f"  no hash   {name}: the entry records no contentHash")
            failures += 1
            continue
        actual = content_hash(directory)
        if actual != recorded:
            print(f"  changed   {name}: {actual[:16]} != {recorded[:16]}")
            failures += 1
        if not entry.get("commit"):
            print(f"  no commit {name}: the entry records no upstream commit")
            failures += 1
    vendored = {path.name for path in SKILLS_ROOT.iterdir() if path.is_dir()}
    for extra in sorted(vendored - set(skills)):
        print(f"  unlocked  {extra}: vendored but absent from the lockfile")
        failures += 1
    print(f"{len(skills)} locked skills, {failures} problems")
    return failures


def write(lock: dict, clone_root: Path | None) -> int:
    skills = lock["skills"]
    untraced = 0
    for name, entry in sorted(skills.items()):
        directory = SKILLS_ROOT / name
        if not directory.is_dir():
            print(f"  missing   {name}")
            untraced += 1
            continue
        entry["contentHash"] = content_hash(directory)
        if clone_root is None:
            continue
        clone = clone_root / entry["source"].split("/")[1]
        skill_directory = str(Path(entry["skillPath"]).parent)
        commit = find_commit(clone, skill_directory, entry["contentHash"])
        if commit:
            entry["commit"] = commit
        else:
            print(f"  untraced  {name}: no upstream commit reproduces the vendored content")
            untraced += 1
    for name in skills:
        skills[name] = {
            key: skills[name][key]
            for key in ("source", "sourceType", "skillPath", "commit", "computedHash", "contentHash")
            if key in skills[name]
        }
    save(lock)
    print(f"wrote {len(skills)} entries, {untraced} untraced")
    return untraced


def verify_upstream(lock: dict, clone_root: Path) -> int:
    failures = 0
    for name, entry in sorted(lock["skills"].items()):
        clone = clone_root / entry["source"].split("/")[1]
        if not clone.is_dir():
            print(f"  no clone  {name}: {clone}")
            failures += 1
            continue
        skill_directory = str(Path(entry["skillPath"]).parent)
        commit = entry.get("commit")
        if not commit:
            print(f"  no commit {name}")
            failures += 1
            continue
        upstream = upstream_hash(clone, commit, skill_directory)
        if upstream != entry.get("contentHash"):
            print(f"  drifted   {name}: {commit[:10]} does not reproduce the vendored content")
            failures += 1
    print(f"provenance: {failures} problems")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="record hashes and commits")
    parser.add_argument(
        "--clones",
        type=Path,
        default=None,
        help="directory holding a clone of each upstream repository",
    )
    parser.add_argument(
        "--verify-upstream",
        type=Path,
        default=None,
        metavar="CLONES",
        help="check every recorded commit against a clone",
    )
    arguments = parser.parse_args()

    lock = load()
    if arguments.write:
        return 1 if write(lock, arguments.clones) else 0
    if arguments.verify_upstream:
        return 1 if verify_upstream(lock, arguments.verify_upstream) else 0
    return 1 if verify(lock) else 0


if __name__ == "__main__":
    sys.exit(main())
