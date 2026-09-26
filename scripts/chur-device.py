#!/usr/bin/env python3
"""Manage the open Chur vault over a user-approved ADB forwarding session."""

import argparse
import base64
import getpass
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

MAX_FRAME = 262_144
SESSION_LINK = re.compile(r"chur://device-control/v1\?port=([0-9]{1,5})#([0-9a-f]{32})")


def parse_session_link(link):
    match = SESSION_LINK.fullmatch(link)
    if match is None or not 1 <= int(match[1]) <= 65535:
        raise ValueError("invalid Chur session link")
    return int(match[1]), match[2]


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


def exchange(connection, request, source=None):
    frame_send(connection, request)
    while True:
        reply = frame_receive(connection)
        if reply.get("op") != "read":
            return reply
        if source is None:
            raise ValueError("unexpected file read")
        offset, size = reply["offset"], reply["size"]
        if not isinstance(offset, int) or not isinstance(size, int) or offset < 0 or not 0 < size <= 65536:
            raise ValueError("invalid file read")
        source.seek(offset)
        data = source.read(size)
        if len(data) != size:
            raise IOError("host file changed during import")
        frame_send(connection, {"op": "data", "bytes": base64.b64encode(data).decode("ascii")})


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
                if progress is not None:
                    progress(offset, size)
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


def command_request(args):
    action = args.action
    if action == "list":
        return {"op": "list", "scope": args.scope, "sort": args.sort, "limit": args.limit,
                "id": args.id or "", "terms": args.terms or "", "cursor": args.cursor or ""}
    if action in ("albums", "tags"):
        return {"op": action}
    if action == "show":
        return {"op": "show", "object": args.object}
    if action in ("album-create", "tag-create"):
        return {"op": action.replace("-", "_"), "name": args.name}
    if action == "album-rename":
        return {"op": "album_rename", "album": args.album, "name": args.name}
    if action == "album-delete":
        if not args.yes:
            raise ValueError("album-delete requires --yes; the album subtree is removed but media is retained")
        return {"op": "album_delete", "album": args.album}
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
    raise ValueError(f"unsupported action: {action}")


def parser():
    cli = argparse.ArgumentParser(description=__doc__)
    session = cli.add_mutually_exclusive_group(required=True)
    session.add_argument("--port", type=int, help="device port shown in Chur settings")
    session.add_argument("--session-stdin", action="store_true", help="read a copied session link from stdin")
    cli.add_argument("--serial", help="ADB device serial, required if several devices are attached")
    cli.add_argument("--code-stdin", action="store_true", help="read the one-session code from stdin")
    commands = cli.add_subparsers(dest="action", required=True)
    listing = commands.add_parser("list", help="list a catalog page")
    listing.add_argument("--scope", choices=("timeline", "favorites", "album", "tag", "search"), default="timeline")
    listing.add_argument("--sort", choices=("capture_desc", "capture_asc", "import_desc", "album_manual"), default="capture_desc")
    listing.add_argument("--limit", type=int, default=100)
    listing.add_argument("--id", help="album or tag ID")
    listing.add_argument("--terms", help="search terms")
    listing.add_argument("--cursor", help="cursor returned by a previous page")
    listing.add_argument("--details", action="store_true", help="fetch filenames and metadata for the page")
    commands.add_parser("albums")
    commands.add_parser("tags")
    commands.add_parser("show").add_argument("object")
    for name in ("album-create", "tag-create"):
        commands.add_parser(name).add_argument("name")
    rename = commands.add_parser("album-rename", help="rename an album")
    rename.add_argument("album")
    rename.add_argument("name")
    delete = commands.add_parser("album-delete", help="delete an album subtree, retaining media")
    delete.add_argument("album")
    delete.add_argument("--yes", action="store_true", help="confirm removal of the album subtree")
    move = commands.add_parser("album-move", help="move an album under a parent, optionally before a sibling")
    move.add_argument("album")
    move.add_argument("--parent")
    move.add_argument("--before")
    reorder = commands.add_parser("album-reorder", help="move an object within an album, before another object")
    reorder.add_argument("album")
    reorder.add_argument("object")
    reorder.add_argument("--before")
    for name, owner in (("album-add", "album"), ("album-remove", "album"),
                        ("tag-add", "tag"), ("tag-remove", "tag")):
        item = commands.add_parser(name)
        item.add_argument(owner)
        item.add_argument("object")
    for name in ("favorite", "unfavorite"):
        commands.add_parser(name).add_argument("object")
    importer = commands.add_parser("import", help="stream one or more local files into Chur")
    importer.add_argument("paths", nargs="+", type=Path)
    importer.add_argument("--type", help="IANA media type override")
    importer.add_argument("--album", help="add each imported object to this album")
    exporter = commands.add_parser("export", help="save one original without overwriting a local file")
    exporter.add_argument("object", help="object ID")
    exporter.add_argument("destination", type=Path, help="new local file path")
    return cli


def main():
    args = parser().parse_args()
    if args.session_stdin:
        if args.code_stdin:
            raise ValueError("--code-stdin cannot be used with --session-stdin")
        link = (getpass.getpass("Paste Chur session link: ") if sys.stdin.isatty()
                else sys.stdin.readline().rstrip("\r\n"))
        port, code = parse_session_link(link)
    else:
        port = args.port
        if not 1 <= port <= 65535:
            raise ValueError("invalid device port")
        code = sys.stdin.readline().strip() if args.code_stdin else getpass.getpass("Code shown in Chur: ").strip()
        if len(code) != 32 or any(char not in "0123456789abcdef" for char in code):
            raise ValueError("invalid pairing code")
    if args.action == "import":
        for path in args.paths:
            if not path.is_file():
                raise ValueError(f"not a regular file: {path}")
    adb = ["adb"] + (["-s", args.serial] if args.serial else [])
    with socket.socket() as reserved:
        reserved.bind(("127.0.0.1", 0))
        local_port = reserved.getsockname()[1]
    subprocess.run(adb + ["forward", "--no-rebind", f"tcp:{local_port}", f"tcp:{port}"],
                   check=True, stdout=subprocess.DEVNULL)
    try:
        with socket.create_connection(("127.0.0.1", local_port), timeout=15) as connection:
            connection.settimeout(None)
            hello = exchange(connection, {"op": "pair", "version": 1, "code": code})
            if hello.get("ok") is not True:
                raise PermissionError(hello.get("error", "pairing rejected"))
            if args.action == "export":
                print(json.dumps(export_file(connection, args.object, args.destination), ensure_ascii=False))
                return 0
            if args.action != "import":
                result = exchange(connection, command_request(args))
                if args.action == "list" and args.details and "error" not in result:
                    for item in result["objects"]:
                        detail = exchange(connection, {"op": "show", "object": item["id"]})
                        if "error" in detail:
                            item["detail_error"] = detail["error"]
                        else:
                            item.update(detail)
                print(json.dumps(result, ensure_ascii=False, indent=2))
                return 1 if "error" in result else 0
            failed = False
            for path in args.paths:
                kind = args.type or mimetypes.guess_type(path.name)[0] or "application/octet-stream"
                with path.open("rb") as source:
                    result = exchange(connection, {"op": "import", "name": path.name,
                                                   "length": path.stat().st_size,
                                                   "content_type": kind}, source)
                if args.album and "id" in result:
                    membership = exchange(connection, {"op": "album_member", "album": args.album,
                                                       "object": result["id"], "member": True})
                    if "error" in membership:
                        result["album_error"] = membership["error"]
                print(json.dumps({"source": str(path), **result}, ensure_ascii=False))
                failed |= "error" in result or "album_error" in result
            return 1 if failed else 0
    finally:
        subprocess.run(adb + ["forward", "--remove", f"tcp:{local_port}"], check=False,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, PermissionError, subprocess.CalledProcessError) as error:
        print(f"chur-device: {error}", file=sys.stderr)
        sys.exit(1)
