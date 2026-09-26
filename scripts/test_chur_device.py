#!/usr/bin/env python3
"""Host protocol checks that do not require a connected Android device."""

import importlib.util
import base64
import io
from pathlib import Path
import socket
import threading
import unittest


SPEC = importlib.util.spec_from_file_location("chur_device", Path(__file__).with_name("chur-device.py"))
device = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(device)


class DeviceProtocolTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
