#!/usr/bin/env python3
"""Host protocol checks that do not require a connected Android device."""

import importlib.util
import base64
import hashlib
import io
import json
from pathlib import Path
import socket
import tempfile
import threading
import unittest
from contextlib import redirect_stdout, redirect_stderr
from unittest.mock import patch
from unittest.mock import MagicMock


SPEC = importlib.util.spec_from_file_location("chur_device", Path(__file__).with_name("chur-device.py"))
device = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(device)


class DeviceProtocolTest(unittest.TestCase):
    def test_paginated_catalog_and_details_do_not_truncate(self):
        ids = [f"{number:032x}" for number in range(1001)]
        calls = []

        def reply(connection, request, *unused):
            calls.append(request)
            offset = int(request.get("cursor") or 0)
            rows = [{"id": value, "name": "tag"} for value in ids[offset:offset + 500]]
            return {"tags": rows, "filename": "photo.jpg",
                    "cursor": str(offset + 500) if offset + 500 < len(ids) else None}

        with patch.object(device, "exchange", side_effect=reply):
            result = device.object_detail(None, "ab" * 16)
        self.assertEqual([row["id"] for row in result["tags"]], ids)
        self.assertEqual([call["cursor"] for call in calls], ["", "500", "1000"])

    def test_repeated_cursor_is_an_error(self):
        with patch.object(device, "exchange", return_value={"albums": [], "cursor": "same"}):
            with self.assertRaisesRegex(ValueError, "repeated"):
                device.collect_pages(None, {"op": "albums"}, "albums")

    def test_main_returns_failure_for_list_details_error(self):
        connection = MagicMock()
        connection.__enter__.return_value = connection
        replies = [{"ok": True, "version": 2}, {"objects": [{"id": "ab" * 16}]},
                   {"error": "NOT_FOUND"}]
        with patch("sys.argv", ["chur-device", "--port", "1234", "--code-stdin", "list", "--details"]), \
                patch("sys.stdin", io.StringIO("a" * 32 + "\n")), \
                patch.object(device.socket, "create_connection", return_value=connection), \
                patch.object(device.subprocess, "run"), \
                patch.object(device, "exchange", side_effect=replies), redirect_stdout(io.StringIO()):
            self.assertEqual(device.main(), 1)

    def test_manifest_preserves_empty_nested_directories_and_refuses_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = str(Path(directory).resolve())
            root = Path(directory) / "photos"
            (root / "nested" / "empty").mkdir(parents=True)
            (root / "nested" / "one.jpg").write_bytes(b"photo")
            manifest = device.import_manifest([root])
            self.assertEqual([Path(row["source"]).name for row in manifest["directories"]],
                             ["photos", "nested", "empty"])
            self.assertEqual(manifest["files"][0]["parent"], str(root / "nested"))
            (root / "linked").symlink_to(root / "nested", target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "symlink"):
                device.import_manifest([root])
            (root / "linked").unlink()
            with self.assertRaisesRegex(ValueError, "overlapping"):
                device.import_manifest([root, root / "nested"])

    def test_transfer_checkpoint_is_private_bound_and_reuses_identities(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = str(Path(directory).resolve())
            source = Path(directory) / "source.bin"
            source.write_bytes(b"original")
            checkpoint = Path(directory) / "checkpoint.json"
            args = device.parser().parse_args(["--port", "1234", "import", str(source),
                                              "--transfer-state", str(checkpoint)])
            _, original = device.prepare_import(args, "vault-one")
            _, resumed = device.prepare_import(args, "vault-one")
            self.assertEqual(original["objects"], resumed["objects"])
            self.assertEqual(checkpoint.stat().st_mode & 0o777, 0o600)
            with self.assertRaisesRegex(ValueError, "another selection or vault"):
                device.prepare_import(args, "vault-two")
            source.write_bytes(b"changed!")
            with self.assertRaisesRegex(ValueError, "sources changed"):
                device.prepare_import(args, "vault-one")

    def test_directory_import_resumes_lost_file_commit_response_without_duplicates(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = str(Path(directory).resolve())
            root = Path(directory) / "root"
            (root / "nested" / "empty").mkdir(parents=True)
            (root / "nested" / "photo.jpg").write_bytes(b"photo")
            checkpoint = Path(directory) / "state.json"
            destination = "d" * 32
            args = device.parser().parse_args(["--port", "1234", "import", str(root),
                                              "--album", destination, "--transfer-state", str(checkpoint)])
            albums, objects, moves, memberships = {}, {}, {}, []
            imports = []

            def reply(connection, request, source=None, progress=None):
                op = request["op"]
                state = json.loads(checkpoint.read_text())
                if op == "album_create":
                    self.assertIn(request["id"], [row["id"] for row in state["albums"].values()])
                    albums[request["id"]] = request["name"]
                    return {"id": request["id"]}
                if op == "album_move":
                    moves[request["album"]] = request["parent"]
                    return {"ok": True}
                if op == "import_status":
                    return {"id": request["object"]} if request["object"] in objects else {"error": "NOT_FOUND"}
                if op == "import":
                    self.assertIn(request["object_id"], state["objects"].values())
                    objects[request["object_id"]] = source.read()
                    imports.append(request["object_id"])
                    raise ConnectionError("lost commit response")
                if op == "album_member":
                    memberships.append((request["album"], request["object"]))
                    return {"ok": True}
                self.fail(f"unexpected operation: {op}")

            with patch.object(device, "exchange", side_effect=reply), redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                with self.assertRaises(ConnectionError):
                    device.run_import(None, args, "vault")
                self.assertEqual(device.run_import(None, args, "vault"), 0)
            self.assertEqual(len(albums), 3)
            self.assertEqual(len(imports), 1)
            self.assertEqual(len(memberships), 1)
            ids = {name: identifier for identifier, name in albums.items()}
            self.assertEqual(moves, {ids["root"]: destination, ids["nested"]: ids["root"], ids["empty"]: ids["nested"]})
            self.assertEqual(memberships[0][0], ids["nested"])

    def test_directory_creation_response_loss_reuses_persisted_album_id(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = str(Path(directory).resolve())
            root = Path(directory) / "empty"
            root.mkdir()
            checkpoint = Path(directory) / "state.json"
            args = device.parser().parse_args(["--port", "1234", "import", str(root),
                                              "--transfer-state", str(checkpoint)])
            ids = []

            def reply(connection, request, *unused):
                ids.append(request["id"])
                if len(ids) == 1:
                    raise ConnectionError("lost album create response")
                return {"id": request["id"]}

            with patch.object(device, "exchange", side_effect=reply), redirect_stderr(io.StringIO()):
                with self.assertRaises(ConnectionError):
                    device.run_import(None, args, "vault")
                self.assertEqual(device.run_import(None, args, "vault"), 0)
            self.assertEqual(ids[0], ids[1])

    def test_export_album_sanitizes_names_preserves_extensions_and_empty_folders(self):
        root_id, child_id, first, second = [char * 32 for char in "abcd"]
        albums = [{"id": root_id, "name": "../../Photos", "parent": None},
                  {"id": child_id, "name": "..\\empty", "parent": root_id}]
        exported = []

        def reply(connection, request, *unused):
            if request["op"] == "albums":
                return {"albums": albums}
            if request["op"] == "list":
                return {"objects": [{"id": first}, {"id": second}] if request["id"] == root_id else []}
            if request["op"] == "show":
                return {"filename": "../../same.jpg", "tags": []}
            self.fail("unexpected request")

        def export(connection, object_id, destination, *unused):
            exported.append(destination)
            destination.write_bytes(b"original")
            return {"object": object_id}

        with tempfile.TemporaryDirectory() as directory:
            directory = str(Path(directory).resolve())
            target = Path(directory) / "export"
            args = device.parser().parse_args(["--port", "1234", "export-album", root_id, str(target)])
            with patch.object(device, "exchange", side_effect=reply), patch.object(device, "export_file", side_effect=export), redirect_stdout(io.StringIO()):
                self.assertEqual(device.run_export_album(None, args), 0)
                with self.assertRaises(FileExistsError):
                    device.run_export_album(None, args)
            self.assertEqual(len(set(exported)), 2)
            self.assertTrue(all(path.is_relative_to(target) and path.suffix == ".jpg" for path in exported))
            self.assertEqual(len(list(target.rglob("*"))), 4)
            with self.assertRaisesRegex(ValueError, "identity"):
                device.safe_component("ok", "../bad")

    def test_note_body_reads_are_chunked_and_revision_guarded(self):
        body = ("😀\\\"\n" * 20000).encode("utf-8")
        calls = []

        def reply(connection, request, *unused):
            calls.append(request)
            if request["op"] == "note_show":
                return {"id": "a" * 32, "revision": 7, "body_size": len(body), "title": "Private"}
            self.assertEqual(request["revision"], 7)
            self.assertLessEqual(request["size"], 65536)
            offset, size = request["offset"], request["size"]
            return {"offset": offset, "bytes": base64.b64encode(body[offset:offset + size]).decode()}

        with patch.object(device, "exchange", side_effect=reply):
            note = device.note_content(None, "a" * 32)
        self.assertEqual(note["body"].encode(), body)
        self.assertGreater(len(calls), 2)

    def test_note_update_preserves_metadata_and_honors_revision(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = str(Path(directory).resolve())
            source = Path(directory) / "body.txt"
            source.write_text("new private body", encoding="utf-8")
            args = device.parser().parse_args(["--port", "1234", "note-update", "a" * 32,
                                              str(source), "--revision", "3"])
            requests = []

            def reply(connection, request, body=None, progress=None):
                requests.append(request)
                if request["op"] == "note_show":
                    return {"id": "a" * 32, "revision": 4, "title": "Existing", "pinned": True}
                self.assertEqual(body.read(), b"new private body")
                return {"id": "a" * 32, "revision": 5}

            with patch.object(device, "exchange", side_effect=reply), redirect_stdout(io.StringIO()):
                self.assertEqual(device.run_note(None, args), 0)
            self.assertEqual(requests[-1], {"op": "note_update", "note": "a" * 32, "revision": 3,
                                           "title": "Existing", "pinned": True, "length": 16})

    def test_note_mutations_and_permanent_delete_confirmation(self):
        for action, operation in (("note-pin", "note_pin"), ("note-unpin", "note_pin"),
                                  ("note-trash", "note_trash"), ("note-restore", "note_restore"),
                                  ("note-delete", "note_delete")):
            args = device.parser().parse_args(["--port", "1234", action, "a" * 32] + (["--yes"] if action == "note-delete" else []))
            with patch.object(device, "exchange", side_effect=[{"revision": 9}, {"ok": True}]) as call, redirect_stdout(io.StringIO()):
                self.assertEqual(device.run_note(None, args), 0)
                self.assertEqual(call.call_args.args[1]["op"], operation)
                self.assertEqual(call.call_args.args[1]["revision"], 9)
        args = device.parser().parse_args(["--port", "1234", "note-delete", "a" * 32])
        with patch.object(device, "exchange") as call:
            with self.assertRaisesRegex(ValueError, "requires --yes"):
                device.run_note(None, args)
            call.assert_not_called()

    def test_private_note_export_is_exclusive_and_private(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = str(Path(directory).resolve())
            target = Path(directory) / "note.txt"
            device.write_private_file(target, b"private")
            self.assertEqual(target.stat().st_mode & 0o777, 0o600)
            with self.assertRaises(FileExistsError):
                device.write_private_file(target, b"replacement")
            self.assertEqual(target.read_bytes(), b"private")

    def test_changed_file_during_import_is_reported_and_checkpoint_is_uncertain(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.bin"
            source.write_bytes(b"original")
            checkpoint = Path(directory) / "state.json"
            args = device.parser().parse_args(["--port", "1234", "import", str(source),
                                              "--transfer-state", str(checkpoint)])

            def reply(connection, request, stream=None, progress=None):
                if request["op"] == "import_status":
                    return {"error": "NOT_FOUND"}
                source.write_bytes(b"changed while sending")
                return {"id": request["object_id"]}

            output = io.StringIO()
            with patch.object(device, "exchange", side_effect=reply), redirect_stdout(output), redirect_stderr(io.StringIO()):
                self.assertEqual(device.run_import(None, args, "vault"), 1)
            row = json.loads(output.getvalue())
            self.assertIn("source_error", row)
            self.assertIn("id", row)
            self.assertEqual(len(json.loads(checkpoint.read_text())["uncertain_sources"]), 1)

    def test_import_checkpoint_cannot_be_inside_source_tree(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "folder"
            root.mkdir()
            args = device.parser().parse_args(["--port", "1234", "import", str(root),
                                              "--transfer-state", str(root / "state.json")])
            with self.assertRaisesRegex(ValueError, "outside imported"):
                device.prepare_import(args, "vault")

    def test_long_preimport_hash_keeps_device_session_alive(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory).resolve() / "file.bin"
            source.write_bytes(b"original")
            item = device.import_manifest([source])["files"][0]
            heartbeat = MagicMock()
            with patch.object(device.time, "monotonic", side_effect=[0, 16, 17]):
                with device.open_import_source(item, heartbeat) as stream:
                    self.assertEqual(stream.read(), b"original")
            heartbeat.assert_called_once_with()

    def test_long_initial_manifest_hash_keeps_session_alive_before_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve() / "folder"
            root.mkdir()
            (root / "file.bin").write_bytes(b"original")
            checkpoint = Path(directory) / "state.json"
            args = device.parser().parse_args(["--port", "1234", "import", str(root),
                                              "--transfer-state", str(checkpoint)])
            calls = []
            clock = 0

            def now():
                nonlocal clock
                clock += 16
                return clock

            def reply(connection, request, stream=None, progress=None):
                calls.append(request["op"])
                if request["op"] == "list":
                    self.assertEqual(request["limit"], 1)
                    return {"objects": []}
                if request["op"] == "album_create":
                    return {"id": request["id"]}
                if request["op"] == "import_status":
                    return {"error": "NOT_FOUND"}
                if request["op"] == "import":
                    self.assertEqual(stream.read(), b"original")
                    return {"id": request["object_id"]}
                return {"ok": True}

            with patch.object(device.time, "monotonic", side_effect=now), \
                    patch.object(device, "exchange", side_effect=reply), \
                    redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                self.assertEqual(device.run_import(None, args, "vault"), 0)
            first_mutation = calls.index("album_create")
            self.assertGreaterEqual(first_mutation, 2)
            self.assertTrue(all(operation == "list" for operation in calls[:first_mutation]))

    def test_manifest_failure_after_keepalive_does_not_mutate_vault(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve() / "folder"
            root.mkdir()
            (root / "symlink").symlink_to(root, target_is_directory=True)
            checkpoint = Path(directory) / "state.json"
            args = device.parser().parse_args(["--port", "1234", "import", str(root),
                                              "--transfer-state", str(checkpoint)])
            with patch.object(device.time, "monotonic", side_effect=[0, 16, 17, 33, 34]), \
                    patch.object(device, "exchange", return_value={"objects": []}) as exchange:
                with self.assertRaisesRegex(ValueError, "symlink"):
                    device.run_import(None, args, "vault")
            self.assertTrue(all(call.args[1]["op"] == "list" for call in exchange.call_args_list))
            self.assertFalse(checkpoint.exists())

    def test_object_trash_tags_and_destruction_contracts(self):
        for action, operation in (("trash", "object_trash"), ("restore", "object_restore")):
            args = device.parser().parse_args(["--port", "1234", action, "a" * 32])
            self.assertEqual(device.command_request(args), {"op": operation, "object": "a" * 32})
        args = device.parser().parse_args(["--port", "1234", "tag-rename", "a" * 32, "New name"])
        self.assertEqual(device.command_request(args), {"op": "tag_rename", "tag": "a" * 32, "name": "New name"})
        for action in ("delete", "tag-delete", "trash-empty", "damaged-delete"):
            argv = ["--port", "1234", action] + (["a" * 32] if action in ("delete", "tag-delete") else [])
            with self.assertRaisesRegex(ValueError, "requires --yes"):
                device.command_request(device.parser().parse_args(argv))
            self.assertIn("op", device.command_request(device.parser().parse_args(argv + ["--yes"])))

    def test_album_commands_and_delete_confirmation(self):
        cli = device.parser()
        args = cli.parse_args(["--port", "1234", "album-move", "a" * 32,
                               "--parent", "b" * 32, "--before", "c" * 32])
        self.assertEqual(device.command_request(args),
                         {"op": "album_move", "album": "a" * 32,
                          "parent": "b" * 32, "before": "c" * 32})
        args = cli.parse_args(["--port", "1234", "album-delete", "a" * 32])
        with self.assertRaisesRegex(ValueError, "requires --yes"):
            device.command_request(args)
        args = cli.parse_args(["--port", "1234", "album-delete", "a" * 32, "--yes"])
        self.assertEqual(device.command_request(args)["op"], "album_delete")
        args = cli.parse_args(["--port", "1234", "import", "--album", "a" * 32, "photo.jpg"])
        self.assertEqual(args.album, "a" * 32)

    def test_batch_previews_before_mutation_and_reports_each_result(self):
        cli = device.parser()
        identifiers = ["a" * 32, "b" * 32]
        for apply in (False, True):
            host, remote = socket.socketpair()

            def app():
                with remote:
                    query = device.frame_receive(remote)
                    self.assertEqual((query["scope"], query["terms"]), ("search", "beach"))
                    device.frame_send(remote, {"objects": [{"id": item} for item in identifiers],
                                               "cursor": None})
                    if apply:
                        request = device.frame_receive(remote)
                        self.assertEqual(request["objects"], identifiers)
                        device.frame_send(remote, {"results": [{"id": identifiers[0], "ok": True},
                                                              {"id": identifiers[1], "error": "NOT_FOUND"}]})

            worker = threading.Thread(target=app)
            worker.start()
            argv = ["--port", "1234", "batch", "favorite", "--scope", "search", "--terms", "beach"]
            args = cli.parse_args(argv + (["--apply"] if apply else []))
            output = io.StringIO()
            with host, redirect_stdout(output):
                code = device.run_batch(host, args)
            worker.join(timeout=5)
            self.assertFalse(worker.is_alive())
            lines = [json.loads(line) for line in output.getvalue().splitlines()]
            self.assertEqual(lines[0]["object_ids"], identifiers)
            self.assertEqual(code, 1 if apply else 0)
            self.assertEqual(len(lines), 3 if apply else 1)

    def test_session_link_accepts_only_the_expected_local_session(self):
        code = "0123456789abcdef" * 2
        self.assertEqual(device.parse_session_link(f"chur://device-control/v2?port=54321#{code}"),
                         (54321, code))
        for link in (f"chur://device-control/v2?port=0#{code}",
                     f"chur://device-control/v2?port=65536#{code}",
                     f"chur://device-control/v2?port=54321#{code.upper()}",
                     f"chur://other/v1?port=54321#{code}",
                     f"chur://device-control/v2?port=54321#{code}&extra=1"):
            with self.subTest(link=link), self.assertRaises(ValueError):
                device.parse_session_link(link)

    def test_import_serves_nonsequential_ranges_and_receives_commit(self):
        host, remote = socket.socketpair()
        observed = []
        progress = []

        def app():
            with remote:
                request = device.frame_receive(remote)
                self.assertEqual(request["op"], "import")
                for offset, size in ((4, 3), (0, 4), (7, 3)):
                    device.frame_send(remote, {"op": "read", "offset": offset, "size": size})
                    observed.append(device.frame_receive(remote)["bytes"])
                device.frame_send(remote, {"id": "0123456789abcdef0123456789abcdef", "derivatives": 2})

        worker = threading.Thread(target=app)
        worker.start()
        with host, io.BytesIO(b"0123456789") as source:
            result = device.exchange(host, {"op": "import"}, source, progress=progress.append)
        worker.join(timeout=5)
        self.assertFalse(worker.is_alive())
        self.assertEqual([base64.b64decode(item) for item in observed],
                         [b"456", b"0123", b"789"])
        self.assertEqual(result["derivatives"], 2)
        self.assertEqual(progress, [3, 7, 10])

    def test_refuses_a_read_past_the_source(self):
        host, remote = socket.socketpair()

        def app():
            with remote:
                device.frame_receive(remote)
                device.frame_send(remote, {"op": "read", "offset": 2, "size": 2})

        worker = threading.Thread(target=app)
        worker.start()
        with host, io.BytesIO(b"abc") as source:
            with self.assertRaises(IOError):
                device.exchange(host, {"op": "import"}, source)
        worker.join(timeout=5)
        self.assertFalse(worker.is_alive())

    def test_export_verifies_hash_and_never_overwrites(self):
        payload = b"Chur original" * 10000
        with tempfile.TemporaryDirectory() as directory:
            directory = str(Path(directory).resolve())
            destination = Path(directory) / "original.bin"
            for corrupt, exists in ((True, False), (False, False), (False, True)):
                host, remote = socket.socketpair()

                def app():
                    with remote:
                        self.assertEqual(device.frame_receive(remote)["op"], "export_begin")
                        device.frame_send(remote, {"size": len(payload), "filename": "original.bin"})
                        offset = 0
                        while offset < len(payload):
                            request = device.frame_receive(remote)
                            self.assertEqual(request["offset"], offset)
                            size = request["size"]
                            device.frame_send(remote, {"offset": offset,
                                                       "bytes": base64.b64encode(payload[offset:offset + size]).decode()})
                            offset += size
                        self.assertEqual(device.frame_receive(remote)["op"], "export_finish")
                        digest = hashlib.sha256(payload).hexdigest()
                        device.frame_send(remote, {"size": len(payload), "sha256": "0" * 64 if corrupt else digest})

                worker = threading.Thread(target=app)
                worker.start()
                with host:
                    if corrupt:
                        with self.assertRaisesRegex(ValueError, "integrity"):
                            device.export_file(host, "ab" * 16, destination)
                        self.assertFalse(destination.exists())
                    elif exists:
                        with self.assertRaises(FileExistsError):
                            device.export_file(host, "ab" * 16, destination)
                        self.assertEqual(destination.read_bytes(), payload)
                    else:
                        result = device.export_file(host, "ab" * 16, destination)
                        self.assertEqual(result["sha256"], hashlib.sha256(payload).hexdigest())
                        self.assertEqual(destination.read_bytes(), payload)
                worker.join(timeout=5)
                self.assertFalse(worker.is_alive())
            self.assertEqual(list(Path(directory).glob(".chur-export-*")), [])


if __name__ == "__main__":
    unittest.main()
