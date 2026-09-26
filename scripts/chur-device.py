#!/usr/bin/env python3
"""Manage the open Chur vault over a user-approved ADB forwarding session."""

import argparse
import base64
import getpass
import json
import mimetypes
from pathlib import Path
import socket
import struct
import subprocess
import sys

MAX_FRAME = 262_144


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
    cli.add_argument("--port", type=int, required=True, help="device port shown in Chur settings")
    cli.add_argument("--serial", help="ADB device serial, required if several devices are attached")
    cli.add_argument("--code-stdin", action="store_true", help="read the one-session code from stdin")
    commands = cli.add_subparsers(dest="action", required=True)
    listing = commands.add_parser("list", help="list a catalog page")
    listing.add_argument("--scope", choices=("timeline", "favorites", "album", "tag", "search"), default="timeline")
    listing.add_argument("--sort", choices=("capture_desc", "capture_asc", "import_desc"), default="capture_desc")
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
    return cli


def main():
    args = parser().parse_args()
    if not 1 <= args.port <= 65535:
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
    subprocess.run(adb + ["forward", "--no-rebind", f"tcp:{local_port}", f"tcp:{args.port}"],
                   check=True, stdout=subprocess.DEVNULL)
    try:
        with socket.create_connection(("127.0.0.1", local_port), timeout=15) as connection:
            connection.settimeout(None)
            hello = exchange(connection, {"op": "pair", "version": 1, "code": code})
            if hello.get("ok") is not True:
                raise PermissionError(hello.get("error", "pairing rejected"))
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
                print(json.dumps({"source": str(path), **result}, ensure_ascii=False))
                failed |= "error" in result
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
