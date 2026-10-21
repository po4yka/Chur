#!/usr/bin/env python3
"""Manage the open Chur vault over a user-approved ADB forwarding session."""

import argparse
import base64
import getpass
import io
import hashlib
import json
import mimetypes
import os
from pathlib import Path
import re
import socket
import struct
import subprocess
import sys
import tempfile
import time
import uuid

MAX_FRAME = 262_144
SOCKET_NAME = re.compile(r"chur-dc-[0-9a-f]{16}")
SESSION_LINK = re.compile(r"chur://device-control/v3\?socket=(chur-dc-[0-9a-f]{16})#([0-9a-f]{32})")


def parse_session_link(link):
    match = SESSION_LINK.fullmatch(link)
    if match is None:
        raise ValueError("invalid Chur session link")
    return match[1], match[2]


def frame_send(connection, message):
    body = json.dumps(message, separators=(",", ":")).encode("utf-8")
    if len(body) > MAX_FRAME:
        raise ValueError("request exceeds protocol limit")
    connection.sendall(struct.pack(">I", len(body)) + body)


def frame_receive(connection):
    def exact(length):
        data = bytearray()
        while len(data) < length:
            chunk = connection.recv(length - len(data))
            if not chunk:
                raise ConnectionError("device disconnected")
            data.extend(chunk)
        return bytes(data)

    length = struct.unpack(">I", exact(4))[0]
    if not 0 < length <= MAX_FRAME:
        raise ValueError("invalid device frame")
    return json.loads(exact(length))


def exchange(connection, request, source=None, progress=None):
    frame_send(connection, request)
    served = 0
    while True:
        reply = frame_receive(connection)
        if reply.get("op") != "read":
            return reply
        if source is None:
            raise ValueError("unexpected file read")
        offset, size = reply["offset"], reply["size"]
        if (not isinstance(offset, int) or isinstance(offset, bool) or
                not isinstance(size, int) or isinstance(size, bool) or offset < 0 or not 0 < size <= 65536):
            raise ValueError("invalid file read")
        source.seek(offset)
        data = source.read(size)
        if len(data) != size:
            raise IOError("host file changed during import")
        frame_send(connection, {"op": "data", "bytes": base64.b64encode(data).decode("ascii")})
        served += size
        if progress is not None:
            progress(served)


class TransferProgress:
    def __init__(self, label, total=None):
        self.label = label
        self.total = total
        self.last_report = 0.0

    def update(self, count):
        now = time.monotonic()
        if now - self.last_report < 0.2 and count != self.total:
            return
        self.last_report = now
        if self.total is None:
            message = f"{self.label}: {count:,} source bytes read"
        else:
            percent = 100 if self.total == 0 else count * 100 // self.total
            message = f"{self.label}: {count:,}/{self.total:,} bytes ({percent}%)"
        if sys.stderr.isatty():
            print("\r" + message, end="", file=sys.stderr, flush=True)
        else:
            print(message, file=sys.stderr)

    def finish(self):
        if sys.stderr.isatty():
            print(file=sys.stderr)


def export_file(connection, object_id, destination, progress=None):
    metadata = exchange(connection, {"op": "export_begin", "object": object_id})
    if "error" in metadata:
        raise ValueError(metadata["error"])
    size = metadata["size"]
    if not isinstance(size, int) or isinstance(size, bool) or size < 0:
        raise ValueError("invalid export size")
    destination = Path(destination)
    fd, temporary = tempfile.mkstemp(prefix=".chur-export-", dir=destination.parent)
    digest = hashlib.sha256()
    reporter = TransferProgress("export", size) if progress is None else None
    progress = progress or reporter.update
    try:
        with os.fdopen(fd, "wb") as output:
            offset = 0
            while offset < size:
                length = min(65_536, size - offset)
                result = exchange(connection, {"op": "export_read", "offset": offset, "size": length})
                if "error" in result:
                    raise ValueError(result["error"])
                if result.get("offset") != offset:
                    raise ValueError("invalid export offset")
                data = base64.b64decode(result["bytes"], validate=True)
                if len(data) != length:
                    raise ValueError("short export read")
                output.write(data)
                digest.update(data)
                offset += length
                progress(offset)
            output.flush()
            os.fsync(output.fileno())
        result = exchange(connection, {"op": "export_finish"})
        if "error" in result:
            raise ValueError(result["error"])
        if result.get("size") != size or result.get("sha256") != digest.hexdigest():
            raise ValueError("export integrity check failed")
        os.link(temporary, destination)
        return {"object": object_id, "destination": str(destination), "size": size,
                "sha256": digest.hexdigest()}
    finally:
        os.unlink(temporary)
        if reporter is not None:
            reporter.finish()


def run_batch(connection, args):
    if not 1 <= args.max_objects <= 10000:
        raise ValueError("--max-objects must be between 1 and 10000")
    if args.scope == "search" and not args.terms:
        raise ValueError("search selection requires --terms")
    if args.scope in ("album", "tag") and not args.id:
        raise ValueError("album and tag selections require --id")
    if args.action_name == "delete" and args.apply and not args.yes:
        raise ValueError("permanent deletion requires --yes")
    if args.action_name.startswith(("album-", "tag-")) and not args.target:
        raise ValueError("album and tag actions require --target")
    ids = []
    cursor = ""
    while True:
        page = exchange(connection, {"op": "list", "scope": args.scope, "sort": "capture_desc",
                                     "limit": 500, "id": args.id or "", "terms": args.terms or "",
                                     "cursor": cursor})
        if "error" in page:
            raise ValueError(page["error"])
        ids.extend(row["id"] for row in page["objects"])
        if len(ids) > args.max_objects:
            raise ValueError(f"selection exceeds --max-objects={args.max_objects}; nothing changed")
        cursor = page.get("cursor") or ""
        if not cursor:
            break
    if len(set(ids)) != len(ids):
        raise ValueError("catalog returned duplicate object IDs; nothing changed")
    preview = {"action": args.action_name, "target": args.target,
               "selection": {"scope": args.scope, "id": args.id, "terms": args.terms},
               "count": len(ids), "object_ids": ids, "apply": args.apply}
    print(json.dumps(preview, ensure_ascii=False))
    if not args.apply:
        return 0
    failures = 0
    for start in range(0, len(ids), 100):
        selected = ids[start:start + 100]
        response = exchange(connection, {"op": "batch", "action": args.action_name,
                                         "target": args.target or "", "objects": selected})
        if "error" in response:
            raise ValueError(f"batch stopped after {start} objects: {response['error']}")
        rows = response["results"]
        if len(rows) != len(selected) or [row.get("id") for row in rows] != selected:
            raise ValueError("invalid batch response")
        for row in rows:
            print(json.dumps(row, ensure_ascii=False))
            failures += "error" in row
    return 1 if failures else 0


def checked(connection, request, source=None, progress=None):
    result = exchange(connection, request, source, progress)
    if "error" in result:
        raise ValueError(f"{request['op']}: {result['error']}")
    return result


def collect_pages(connection, request, key):
    """Collect complete catalogs without silently truncating at a frame/page limit."""
    rows, seen = [], set()
    cursor = request.get("cursor") or ""
    while True:
        result = checked(connection, {**request, "cursor": cursor})
        rows.extend(result[key])
        cursor = result.get("cursor") or ""
        if not cursor:
            return {**result, key: rows, "cursor": None}
        if cursor in seen:
            raise ValueError("device repeated a pagination cursor")
        seen.add(cursor)


def object_detail(connection, object_id):
    return collect_pages(connection, {"op": "show", "object": object_id, "limit": 500}, "tags")


def command_request(args):
    action = args.action
    if action == "list":
        return {"op": "list", "scope": args.scope, "sort": args.sort, "limit": args.limit,
                "id": args.id or "", "terms": args.terms or "", "cursor": args.cursor or ""}
    if action in ("albums", "tags"):
        return {"op": action, "limit": 500}
    if action == "show":
        return {"op": "show", "object": args.object, "limit": 500}
    if action in ("album-create", "tag-create"):
        result = {"op": action.replace("-", "_"), "name": args.name}
        if action == "album-create":
            result["id"] = uuid.uuid4().hex
        return result
    if action in ("album-rename", "tag-rename"):
        owner = action.split("-")[0]
        return {"op": action.replace("-", "_"), owner: getattr(args, owner), "name": args.name}
    if action in ("album-delete", "tag-delete"):
        if not args.yes:
            raise ValueError(f"{action} requires --yes")
        owner = action.split("-")[0]
        return {"op": action.replace("-", "_"), owner: getattr(args, owner)}
    if action == "album-move":
        return {"op": "album_move", "album": args.album,
                "parent": args.parent or "", "before": args.before or ""}
    if action == "album-reorder":
        return {"op": "album_reorder", "album": args.album, "object": args.object,
                "before": args.before or ""}
    if action in ("album-add", "album-remove"):
        return {"op": "album_member", "album": args.album, "object": args.object,
                "member": action == "album-add"}
    if action in ("tag-add", "tag-remove"):
        return {"op": "object_tag", "tag": args.tag, "object": args.object,
                "tagged": action == "tag-add"}
    if action in ("favorite", "unfavorite"):
        return {"op": "favorite", "object": args.object, "favorite": action == "favorite"}
    if action in ("trash", "restore", "delete"):
        if action == "delete" and not args.yes:
            raise ValueError("delete requires --yes; this permanently deletes the original")
        return {"op": "object_" + action, "object": args.object}
    if action in ("trash-empty", "trash-restore", "damaged-delete"):
        if action != "trash-restore" and not args.yes:
            raise ValueError(f"{action} requires --yes; this permanently deletes originals")
        return {"op": action.replace("-", "_")}
    if action == "quarantine":
        return {"op": "list", "scope": "quarantine", "limit": 500, "sort": "capture_desc"}
    if action == "notes":
        return {"op": "notes", "scope": args.scope, "terms": args.terms or "", "limit": 100}
    raise ValueError(f"unsupported action: {action}")


def save_state(path, state):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".chur-transfer-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            json.dump(state, output, ensure_ascii=False, indent=2)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        directory_fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def import_manifest(paths, heartbeat=None):
    """Snapshot regular files and all directories, refusing symlinks before mutation."""
    directories, files, seen = [], [], set()
    last_heartbeat = time.monotonic()

    def keep_alive():
        nonlocal last_heartbeat
        if heartbeat is not None and time.monotonic() - last_heartbeat >= 15:
            heartbeat()
            last_heartbeat = time.monotonic()

    def visit(path, parent):
        keep_alive()
        if path.is_symlink():
            raise ValueError(f"symlinks are not imported: {path}")
        key = str(path.absolute())
        if key in seen:
            raise ValueError(f"overlapping import paths: {path}")
        seen.add(key)
        if path.is_dir():
            if not path.name.strip():
                raise ValueError("import a named directory rather than a filesystem root")
            directories.append({"source": key, "parent": parent, "name": path.name})
            for child in sorted(path.iterdir()):
                visit(child, key)
        elif path.is_file():
            digest = hashlib.sha256()
            with path.open("rb") as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(chunk)
                    keep_alive()
            files.append({"source": key, "parent": parent, "size": path.stat().st_size,
                          "sha256": digest.hexdigest()})
        else:
            raise ValueError(f"not a regular file or directory: {path}")

    for path in paths:
        absolute = Path(os.path.abspath(path))
        if absolute.is_symlink():
            raise ValueError(f"symlink path is not imported: {path}")
        # Resolve the explicitly selected parent (macOS /var and /tmp are aliases),
        # then reject symlinks inside the selected tree rather than following them.
        absolute = absolute.parent.resolve() / absolute.name
        visit(absolute, None)
    return {"directories": directories, "files": files}


def prepare_import(args, vault_id, heartbeat=None):
    if not vault_id:
        raise ValueError("device did not identify the vault; cannot safely resume imports")
    manifest = import_manifest(args.paths, heartbeat)
    selection = {"paths": [str(path.absolute()) for path in args.paths], "album": args.album,
                 "type": args.type}
    selection_id = hashlib.sha256(json.dumps([vault_id, selection], sort_keys=True).encode()).hexdigest()
    state_path = args.transfer_state or (Path.home() / ".local/state/chur-device" / f"{selection_id}.json")
    resolved_state = state_path.resolve()
    if any(resolved_state.is_relative_to(Path(row["source"])) for row in manifest["directories"]):
        raise ValueError("--transfer-state must be outside imported directories")
    if state_path.exists():
        if state_path.is_symlink():
            raise ValueError("transfer checkpoint cannot be a symlink")
        state = json.loads(state_path.read_text(encoding="utf-8"))
        if state.get("version") != 2 or state.get("selection") != selection or state.get("vault") != vault_id:
            raise ValueError("transfer checkpoint belongs to another selection or vault; choose a new --transfer-state")
        if state.get("manifest") != manifest:
            raise ValueError("import sources changed since checkpoint; choose a new --transfer-state")
    else:
        state = {"version": 2, "selection": selection, "vault": vault_id, "manifest": manifest,
                 "albums": {}, "objects": {row["source"]: uuid.uuid4().hex for row in manifest["files"]}}
        save_state(state_path, state)
    return state_path, state


def open_import_source(item, heartbeat=None):
    path = Path(item["source"])
    if any(part.is_symlink() for part in [path, *path.parents]):
        raise ValueError(f"import source became a symlink: {path}")
    source = os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW), "rb")
    try:
        digest = hashlib.sha256()
        last_heartbeat = time.monotonic()
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
            if heartbeat is not None and time.monotonic() - last_heartbeat >= 15:
                heartbeat()
                last_heartbeat = time.monotonic()
        if os.fstat(source.fileno()).st_size != item["size"] or digest.hexdigest() != item["sha256"]:
            raise ValueError(f"import source changed: {path}")
        source.seek(0)
        return source
    except BaseException:
        source.close()
        raise


def run_import(connection, args, vault_id):
    def catalog_heartbeat():
        checked(connection, {"op": "list", "scope": "timeline", "sort": "capture_desc", "limit": 1})

    # Validate and hash the entire selection before mutating the vault while
    # keeping the authenticated session alive for arbitrarily large trees.
    state_path, state = prepare_import(args, vault_id, catalog_heartbeat)
    print(f"Transfer checkpoint: {state_path}", file=sys.stderr)
    albums = state["albums"]
    for folder in state["manifest"]["directories"]:
        key = folder["source"]
        parent = albums[folder["parent"]]["id"] if folder["parent"] else args.album
        entry = albums.get(key)
        if entry is None:
            entry = albums[key] = {"id": uuid.uuid4().hex, "parent": parent, "name": folder["name"]}
            save_state(state_path, state)
        result = checked(connection, {"op": "album_create", "id": entry["id"], "name": folder["name"]})
        if result.get("id") != entry["id"]:
            raise ValueError("device returned a different album identity")
        if parent:
            checked(connection, {"op": "album_move", "album": entry["id"], "parent": parent, "before": ""})
    failures = 0
    for item in state["manifest"]["files"]:
        path = Path(item["source"])
        object_id = state["objects"][item["source"]]
        if item["source"] in state.get("uncertain_sources", []):
            raise ValueError(f"source changed while sending {path}; inspect object {object_id} before starting a new transfer")
        result = exchange(connection, {"op": "import_status", "object": object_id})
        if result.get("error") == "NOT_FOUND":
            progress = TransferProgress(f"import {path.name}")

            def heartbeat():
                status = exchange(connection, {"op": "import_status", "object": object_id})
                if "error" in status and status["error"] != "NOT_FOUND":
                    raise ValueError(f"import_status: {status['error']}")

            with open_import_source(item, heartbeat) as source:
                before = os.fstat(source.fileno())
                try:
                    result = exchange(connection, {"op": "import", "object_id": object_id,
                                       "name": path.name, "length": item["size"],
                                       "content_type": args.type or mimetypes.guess_type(path.name)[0]
                                       or "application/octet-stream"}, source, progress.update)
                finally:
                    progress.finish()
                    after = os.fstat(source.fileno())
                    changed = (before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (after.st_size, after.st_mtime_ns, after.st_ctime_ns)
                    if changed:
                        state.setdefault("uncertain_sources", []).append(item["source"])
                        save_state(state_path, state)
                if changed:
                    result["source_error"] = "source changed during transfer; imported object content must be inspected"
        if "error" not in result and result.get("id") != object_id:
            raise ValueError("device returned a different import identity")
        album = albums[item["parent"]]["id"] if item["parent"] else args.album
        if album and "error" not in result:
            membership = exchange(connection, {"op": "album_member", "album": album,
                                               "object": object_id, "member": True})
            if "error" in membership:
                result["album_error"] = membership["error"]
        print(json.dumps({"source": str(path), **result}, ensure_ascii=False))
        failures += "error" in result or "album_error" in result or "source_error" in result
    return 1 if failures else 0


def safe_component(name, identifier):
    if re.fullmatch(r"[0-9a-f]{32}", identifier) is None:
        raise ValueError("invalid catalog identity")
    # Device names are untrusted, including macOS separators and Unicode controls.
    name = re.sub(r'[\\/:\x00-\x1f\x7f]', "_", str(name))
    name = "".join(char for char in name if char.isprintable()).strip(" .")
    if not name or name in (".", ".."):
        name = "item"
    # Bound UTF-8 bytes; identifiers disambiguate case-insensitive/normalizing filesystems.
    while len(name.encode("utf-8")) > 170:
        name = name[:-1]
    return f"{name}--{identifier}"


def run_export_album(connection, args):
    albums = collect_pages(connection, {"op": "albums", "limit": 500}, "albums")["albums"]
    by_id = {row["id"]: row for row in albums}
    if args.album not in by_id:
        raise ValueError("album not found")
    destination = args.destination
    # An existing destination is never reused, including symlinks.
    destination.mkdir(mode=0o700)
    visited = set()

    def export_album(album_id, target):
        if album_id in visited:
            raise ValueError("album tree contains a cycle or duplicate ID")
        visited.add(album_id)
        target.mkdir(mode=0o700)
        rows = collect_pages(connection, {"op": "list", "scope": "album", "id": album_id,
                             "sort": "album_manual", "limit": 500}, "objects")["objects"]
        seen_objects = set()
        for row in rows:
            object_id = row["id"]
            if object_id in seen_objects:
                raise ValueError("album returned duplicate object IDs")
            seen_objects.add(object_id)
            detail = object_detail(connection, object_id)
            original = str(detail["filename"])
            suffix = Path(original.replace("\\", "_")).suffix
            if not re.fullmatch(r"\.[a-zA-Z0-9]{1,12}", suffix):
                suffix = ""
            filename = safe_component(original[:-len(suffix)] if suffix else original, object_id) + suffix
            print(json.dumps(export_file(connection, object_id, target / filename), ensure_ascii=False))
        for child in albums:
            if child.get("parent") == album_id:
                export_album(child["id"], target / safe_component(child["name"], child["id"]))

    root = by_id[args.album]
    export_album(args.album, destination / safe_component(root["name"], args.album))
    return 0


def note_content(connection, note_id):
    result = checked(connection, {"op": "note_show", "note": note_id})
    size = result["body_size"]
    if not isinstance(size, int) or isinstance(size, bool) or not 0 <= size <= 262144:
        raise ValueError("invalid note body size")
    body = bytearray()
    while len(body) < size:
        length = min(65536, size - len(body))
        part = checked(connection, {"op": "note_read", "note": note_id,
                       "revision": result["revision"], "offset": len(body), "size": length})
        if part.get("offset") != len(body):
            raise ValueError("invalid note read offset")
        data = base64.b64decode(part["bytes"], validate=True)
        if len(data) != length:
            raise ValueError("short note read")
        body.extend(data)
    result["body"] = body.decode("utf-8")
    return result


def write_private_file(destination, content):
    # Exclusive creation prevents both overwrites and following destination symlinks.
    fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as output:
        output.write(content)
        output.flush()
        os.fsync(output.fileno())


def run_note(connection, args):
    action = args.action
    if action in ("note-show", "note-export"):
        result = note_content(connection, args.note)
        if action == "note-export":
            write_private_file(args.destination, result["body"].encode("utf-8"))
            result.pop("body")
            result["destination"] = str(args.destination)
    elif action in ("note-create", "note-import", "note-update"):
        with args.source.open("rb") as source:
            body = source.read(262145)
        if len(body) > 262144:
            raise ValueError("note body exceeds 262144 UTF-8 bytes")
        body.decode("utf-8")
        old = checked(connection, {"op": "note_show", "note": args.note}) if action == "note-update" else {}
        title = args.title if args.title is not None else old.get("title", args.source.stem)
        pinned = args.pinned if args.pinned is not None else old.get("pinned", False)
        request = {"op": "note_update" if old else "note_create", "title": title,
                   "pinned": pinned, "length": len(body)}
        if old:
            request.update(note=args.note, revision=args.revision or old["revision"])
        result = checked(connection, request, io.BytesIO(body))
    else:
        if action == "note-delete" and not args.yes:
            raise ValueError("note-delete requires --yes; the note is permanently deleted")
        old = checked(connection, {"op": "note_show", "note": args.note})
        request = {"op": "note_pin" if action in ("note-pin", "note-unpin") else action.replace("-", "_"),
                   "note": args.note, "revision": args.revision or old["revision"]}
        if action in ("note-pin", "note-unpin"):
            request["pinned"] = action == "note-pin"
        result = checked(connection, request)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


def parser():
    cli = argparse.ArgumentParser(description=__doc__)
    session = cli.add_mutually_exclusive_group(required=True)
    session.add_argument("--socket", help="device socket name shown in Chur settings")
    session.add_argument("--session-stdin", action="store_true", help="read a copied session link from stdin")
    cli.add_argument("--serial", help="ADB device serial, required if several devices are attached")
    cli.add_argument("--code-stdin", action="store_true", help="read the one-session code from stdin")
    commands = cli.add_subparsers(dest="action", required=True)
    listing = commands.add_parser("list", help="list a catalog page")
    listing.add_argument("--scope", choices=("timeline", "favorites", "album", "tag", "search", "trash", "quarantine"), default="timeline")
    listing.add_argument("--sort", choices=("capture_desc", "capture_asc", "import_desc", "album_manual"), default="capture_desc")
    listing.add_argument("--limit", type=int, default=100)
    listing.add_argument("--id", help="album or tag ID")
    listing.add_argument("--terms", help="search terms")
    listing.add_argument("--cursor", help="cursor returned by a previous page")
    listing.add_argument("--all", action="store_true", help="fetch every page")
    listing.add_argument("--details", action="store_true", help="fetch filenames and metadata")
    for name in ("albums", "tags", "quarantine"):
        commands.add_parser(name)
    commands.add_parser("show").add_argument("object")
    for name in ("album-create", "tag-create"):
        commands.add_parser(name).add_argument("name")
    for owner in ("album", "tag"):
        rename = commands.add_parser(owner + "-rename")
        rename.add_argument(owner)
        rename.add_argument("name")
        delete = commands.add_parser(owner + "-delete")
        delete.add_argument(owner)
        delete.add_argument("--yes", action="store_true")
    move = commands.add_parser("album-move")
    move.add_argument("album")
    move.add_argument("--parent")
    move.add_argument("--before")
    reorder = commands.add_parser("album-reorder")
    reorder.add_argument("album")
    reorder.add_argument("object")
    reorder.add_argument("--before")
    for name, owner in (("album-add", "album"), ("album-remove", "album"),
                        ("tag-add", "tag"), ("tag-remove", "tag")):
        item = commands.add_parser(name)
        item.add_argument(owner)
        item.add_argument("object")
    for name in ("favorite", "unfavorite", "trash", "restore", "delete"):
        item = commands.add_parser(name)
        item.add_argument("object")
        if name == "delete":
            item.add_argument("--yes", action="store_true")
    for name in ("trash-empty", "trash-restore", "damaged-delete"):
        item = commands.add_parser(name)
        if name != "trash-restore":
            item.add_argument("--yes", action="store_true")
    importer = commands.add_parser("import", help="import files/directories preserving directory albums, resumable")
    importer.add_argument("paths", nargs="+", type=Path)
    importer.add_argument("--type", help="IANA media type override")
    importer.add_argument("--album", help="destination album; directory roots become children")
    importer.add_argument("--transfer-state", type=Path, help="private durable import checkpoint; reuse to resume")
    exporter = commands.add_parser("export", help="save one original without overwriting a local file")
    exporter.add_argument("object")
    exporter.add_argument("destination", type=Path)
    tree = commands.add_parser("export-album", help="export an album subtree into a new directory")
    tree.add_argument("album")
    tree.add_argument("destination", type=Path)
    batch = commands.add_parser("batch", help="preview or apply an action to a query selection")
    batch.add_argument("action_name", choices=("album-add", "album-remove", "tag-add", "tag-remove",
                       "favorite", "unfavorite", "trash", "restore", "delete"))
    batch.add_argument("--target", help="album or tag ID for the action")
    batch.add_argument("--scope", choices=("timeline", "favorites", "album", "tag", "search", "trash", "quarantine"), default="timeline")
    batch.add_argument("--id")
    batch.add_argument("--terms")
    batch.add_argument("--max-objects", type=int, default=1000)
    batch.add_argument("--apply", action="store_true")
    batch.add_argument("--yes", action="store_true", help="confirm permanent deletion")
    notes = commands.add_parser("notes", help="list/search private notes, including trash")
    notes.add_argument("--scope", choices=("active", "trash"), default="active")
    notes.add_argument("--terms")
    for name in ("note-show", "note-export", "note-trash", "note-restore", "note-delete", "note-pin", "note-unpin"):
        note = commands.add_parser(name)
        note.add_argument("note")
        if name == "note-export":
            note.add_argument("destination", type=Path)
        elif name != "note-show":
            note.add_argument("--revision", type=int, help="require this expected revision")
            if name == "note-delete":
                note.add_argument("--yes", action="store_true")
    for name in ("note-create", "note-import", "note-update"):
        note = commands.add_parser(name, help="save a UTF-8 text file as a private note")
        if name == "note-update":
            note.add_argument("note")
            note.add_argument("--revision", type=int)
        note.add_argument("source", type=Path)
        note.add_argument("--title")
        note.add_argument("--pinned", action=argparse.BooleanOptionalAction, default=None)
    return cli


def run_connected(connection, args, hello):
    if args.action == "import":
        return run_import(connection, args, hello.get("vault_id"))
    if args.action == "export":
        print(json.dumps(export_file(connection, args.object, args.destination), ensure_ascii=False))
        return 0
    if args.action == "export-album":
        return run_export_album(connection, args)
    if args.action == "batch":
        return run_batch(connection, args)
    if args.action.startswith("note-"):
        return run_note(connection, args)
    if args.action in ("albums", "tags", "notes", "quarantine") or (args.action == "list" and args.all):
        key = {"quarantine": "objects", "list": "objects"}.get(args.action, args.action)
        result = collect_pages(connection, command_request(args), key)
    elif args.action == "show":
        result = object_detail(connection, args.object)
    else:
        result = exchange(connection, command_request(args))
    failed = "error" in result
    if args.action == "list" and args.details and not failed:
        for item in result["objects"]:
            try:
                item.update(object_detail(connection, item["id"]))
            except ValueError as error:
                item["detail_error"] = str(error)
                failed = True
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 1 if failed else 0


def main():
    args = parser().parse_args()
    if args.session_stdin:
        if args.code_stdin:
            raise ValueError("--code-stdin cannot be used with --session-stdin")
        link = (getpass.getpass("Paste Chur session link: ") if sys.stdin.isatty()
                else sys.stdin.readline().rstrip("\r\n"))
        socket_name, code = parse_session_link(link)
    else:
        socket_name = args.socket
        if SOCKET_NAME.fullmatch(socket_name) is None:
            raise ValueError("invalid device socket name")
        code = sys.stdin.readline().strip() if args.code_stdin else getpass.getpass("Code shown in Chur: ").strip()
        if len(code) != 32 or any(char not in "0123456789abcdef" for char in code):
            raise ValueError("invalid pairing code")
    adb = ["adb"] + (["-s", args.serial] if args.serial else [])
    with socket.socket() as reserved:
        reserved.bind(("127.0.0.1", 0))
        local_port = reserved.getsockname()[1]
    try:
        subprocess.run(adb + ["forward", "--no-rebind", f"tcp:{local_port}", f"localabstract:{socket_name}"],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except subprocess.CalledProcessError:
        raise RuntimeError("ADB forwarding failed; check USB debugging, device authorization and --serial") from None
    try:
        try:
            connection = socket.create_connection(("127.0.0.1", local_port), timeout=15)
        except OSError:
            raise RuntimeError("cannot reach Chur; keep Control from computer open and the vault unlocked") from None
        with connection:
            connection.settimeout(None)
            hello = exchange(connection, {"op": "pair", "version": 2, "code": code})
            if hello.get("ok") is not True or hello.get("version") != 2:
                raise PermissionError("session code or protocol rejected; update Chur and copy a fresh link")
            return run_connected(connection, args, hello)
    finally:
        subprocess.run(adb + ["forward", "--remove", f"tcp:{local_port}"], check=False,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, PermissionError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"chur-device: {error}", file=sys.stderr)
        sys.exit(1)
