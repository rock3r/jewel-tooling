import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("marketplace", Path(__file__).with_name("marketplace-screenshots.py"))
marketplace = importlib.util.module_from_spec(spec)
spec.loader.exec_module(marketplace)


def png(width, height, pixel):
    return marketplace.write_png(bytes(pixel) * (width * height), width, height)


class MarketplaceScreenshotsTest(unittest.TestCase):
    def test_roundtrip_rgba(self):
        data = png(2, 2, (1, 2, 3, 255))
        width, height, pixels = marketplace.read_png(data)
        self.assertEqual((width, height), (2, 2))
        self.assertEqual(pixels, bytes((1, 2, 3, 255)) * 4)

    def test_taller_top_bias_keeps_the_header(self):
        width, height = 32, 40
        pixels = bytearray(width * height * 4)
        for y in range(height):
            color = (255, 0, 0, 255) if y < 12 else (0, 0, 255, 255)
            for x in range(width):
                pixels[(y * width + x) * 4:(y * width + x) * 4 + 4] = color
        rendered = marketplace.render(marketplace.write_png(bytes(pixels), width, height), "top")
        _, _, out = marketplace.read_png(rendered)
        self.assertEqual(marketplace.read_png(rendered)[:2], (1280, 800))
        left = (1280 - 32) // 2
        top = (800 - 20) // 2
        sample = (top * 1280 + left) * 4
        self.assertEqual(tuple(out[sample:sample + 3]), (255, 0, 0))

    def test_downscale_does_not_upscale(self):
        rendered = marketplace.render(png(1600, 1000, (10, 20, 30, 255)), "center")
        width, height, pixels = marketplace.read_png(rendered)
        self.assertEqual((width, height), (1280, 800))
        self.assertEqual(tuple(pixels[:4]), (10, 20, 30, 255))
        self.assertEqual(tuple(pixels[-4:]), (10, 20, 30, 255))

    def test_write_and_verify_from_guide_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            images = root / "user-guide" / "images"
            images.mkdir(parents=True)
            for _, source, _, _ in marketplace.SCREENS:
                (images / source).write_bytes(png(1600, 1000, (40, 50, 60, 255)))
            marketplace.write(root)
            marketplace.verify(root)
            (root / "user-guide/images/marketplace/editor.png").write_bytes(png(1280, 800, (1, 1, 1, 255)))
            with self.assertRaisesRegex(ValueError, "stale"):
                marketplace.verify(root)
