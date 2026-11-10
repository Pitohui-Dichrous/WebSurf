import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.parse import unquote

from fixture_server import media_file


class MediaPathTests(unittest.TestCase):
    def test_remote_and_device_paths_are_rejected_before_lookup(self):
        inputs = [
            "%5C%5Cserver%5Cshare%5Cfile", "%2F%2Fserver/share/file",
            "%5C%5C%3F%5CUNC%5Cserver%5Cshare", "C%3A/file",
            "hls/C%3A/file", "../outside", "hls/..%20/outside",
            "NUL.mp4", "hls/COM1", "clip.mp4%3Asecret",
        ]
        with patch.object(Path, "resolve", side_effect=AssertionError("unexpected path lookup")):
            for value in inputs:
                with self.subTest(path=value):
                    self.assertIsNone(media_file(unquote(value)))

    def test_nested_fixture_is_served(self):
        self.assertTrue(media_file("hls/renditions/360/index.m3u8").is_file())
        self.assertIsNone(media_file("hls/missing.m3u8"))


if __name__ == "__main__":
    unittest.main()
