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
from contextlib import redirect_stdout


SPEC = importlib.util.spec_from_file_location("chur_device", Path(__file__).with_name("chur-device.py"))
device = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(device)


class DeviceProtocolTest(unittest.TestCase):
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
        self.assertEqual(device.parse_session_link(f"chur://device-control/v1?port=54321#{code}"),
                         (54321, code))
        for link in (f"chur://device-control/v1?port=0#{code}",
                     f"chur://device-control/v1?port=65536#{code}",
                     f"chur://device-control/v1?port=54321#{code.upper()}",
                     f"chur://other/v1?port=54321#{code}",
                     f"chur://device-control/v1?port=54321#{code}&extra=1"):
            with self.subTest(link=link), self.assertRaises(ValueError):
                device.parse_session_link(link)

    def test_import_serves_nonsequential_ranges_and_receives_commit(self):
        host, remote = socket.socketpair()
        observed = []

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
            result = device.exchange(host, {"op": "import"}, source)
        worker.join(timeout=5)
        self.assertFalse(worker.is_alive())
        self.assertEqual([base64.b64decode(item) for item in observed],
                         [b"456", b"0123", b"789"])
        self.assertEqual(result["derivatives"], 2)

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
            destination = Path(directory) / "original.bin"
            for corrupt in (True, False):
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
                    else:
                        result = device.export_file(host, "ab" * 16, destination)
                        self.assertEqual(result["sha256"], hashlib.sha256(payload).hexdigest())
                        self.assertEqual(destination.read_bytes(), payload)
                worker.join(timeout=5)
                self.assertFalse(worker.is_alive())
            self.assertEqual(list(Path(directory).glob(".chur-export-*")), [])


if __name__ == "__main__":
    unittest.main()
