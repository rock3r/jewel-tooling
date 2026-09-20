#!/usr/bin/env python3
"""Crop committed Retina captures to JetBrains Marketplace 1280x800 screenshots."""
import argparse
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
WIDTH = 1280
HEIGHT = 800
ASPECT = WIDTH / HEIGHT
SIGNATURE = b"\x89PNG\r\n\x1a\n"
SCREENS = (
    ("editor.png", "standalone-editor.png", "center",
     "Compose parameter stability hints beside @Composable parameters."),
    ("live.png", "live-standalone.png", "top",
     "Live recompositions from a local run, with execution counts and inclusive durations."),
    ("recording.png", "recording-standalone.png", "top",
     "A saved Compose recording with a site filter and per-site inclusive duration."),
)


def destination(root):
    return root / "user-guide" / "images" / "marketplace"


def source_path(root, filename):
    return root / "user-guide" / "images" / filename


def read_png(data):
    if data[:8] != SIGNATURE:
        raise ValueError("Expected a PNG screenshot")
    width = height = color = None
    payload = []
    offset = 8
    while offset < len(data):
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        kind = data[offset + 4:offset + 8]
        chunk = data[offset + 8:offset + 8 + length]
        crc = struct.unpack(">I", data[offset + 8 + length:offset + 12 + length])[0]
        if zlib.crc32(kind + chunk) & 0xFFFFFFFF != crc:
            raise ValueError("PNG chunk checksum failed")
        if kind == b"IHDR":
            width, height, depth, color, compression, filt, interlace = struct.unpack(">IIBBBBB", chunk)
            if (depth, color, compression, filt, interlace) != (8, 6, 0, 0, 0):
                raise ValueError("Marketplace source must be 8-bit RGBA")
        elif kind == b"IDAT":
            payload.append(chunk)
        elif kind == b"IEND":
            break
        offset += 12 + length
    if width is None:
        raise ValueError("PNG header is missing")
    return width, height, unfilter(zlib.decompress(b"".join(payload)), width, height)


def paeth(left, up, corner):
    estimate = left + up - corner
    pack = abs(estimate - left), abs(estimate - up), abs(estimate - corner)
    if pack[0] <= pack[1] and pack[0] <= pack[2]:
        return left
    if pack[1] <= pack[2]:
        return up
    return corner


def unfilter(raw, width, height):
    stride = width * 4
    expected = height * (stride + 1)
    if len(raw) != expected:
        raise ValueError("PNG scanlines do not match the header")
    rows = bytearray(height * stride)
    for y in range(height):
        start = y * (stride + 1)
        filt = raw[start]
        row = bytearray(raw[start + 1:start + 1 + stride])
        prior = rows[(y - 1) * stride:y * stride] if y else bytearray(stride)
        if filt == 1:
            for i, value in enumerate(row):
                row[i] = (value + (row[i - 4] if i >= 4 else 0)) & 255
        elif filt == 2:
            for i, value in enumerate(row):
                row[i] = (value + prior[i]) & 255
        elif filt == 3:
            for i, value in enumerate(row):
                left = row[i - 4] if i >= 4 else 0
                row[i] = (value + ((left + prior[i]) // 2)) & 255
        elif filt == 4:
            for i, value in enumerate(row):
                left = row[i - 4] if i >= 4 else 0
                corner = prior[i - 4] if i >= 4 else 0
                row[i] = (value + paeth(left, prior[i], corner)) & 255
        elif filt:
            raise ValueError("Unsupported PNG filter")
        rows[y * stride:(y + 1) * stride] = row
    return bytes(rows)


def write_png(pixels, width, height):
    stride = width * 4
    if len(pixels) != stride * height:
        raise ValueError("Pixel buffer does not match dimensions")
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        raw.extend(pixels[y * stride:(y + 1) * stride])

    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return SIGNATURE + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")


def crop_rect(width, height, bias):
    if width / height > ASPECT:
        cropped_width = min(width, round(height * ASPECT))
        return (width - cropped_width) // 2, 0, cropped_width, height
    cropped_height = min(height, round(width / ASPECT))
    top = 0 if bias == "top" else (height - cropped_height) // 2
    return 0, top, width, cropped_height


def crop(pixels, width, height, left, top, cropped_width, cropped_height):
    stride = width * 4
    rows = bytearray(cropped_width * cropped_height * 4)
    for y in range(cropped_height):
        start = ((top + y) * stride) + left * 4
        rows[y * cropped_width * 4:(y + 1) * cropped_width * 4] = pixels[start:start + cropped_width * 4]
    return bytes(rows)


def scale(pixels, width, height, target_width, target_height):
    if (width, height) == (target_width, target_height):
        return pixels
    if width < target_width or height < target_height:
        raise ValueError("Marketplace screenshots must not upscale")
    output = bytearray(target_width * target_height * 4)
    for y in range(target_height):
        y0 = y * height // target_height
        y1 = max(y0 + 1, (y + 1) * height // target_height)
        for x in range(target_width):
            x0 = x * width // target_width
            x1 = max(x0 + 1, (x + 1) * width // target_width)
            totals = [0, 0, 0, 0]
            count = (x1 - x0) * (y1 - y0)
            for row in range(y0, y1):
                start = (row * width + x0) * 4
                for column in range(x1 - x0):
                    pixel = start + column * 4
                    for channel in range(4):
                        totals[channel] += pixels[pixel + channel]
            dest = (y * target_width + x) * 4
            for channel, total in enumerate(totals):
                output[dest + channel] = total // count
    return bytes(output)


def letterbox(pixels, width, height):
    canvas = bytearray(WIDTH * HEIGHT * 4)
    left = (WIDTH - width) // 2
    top = (HEIGHT - height) // 2
    for y in range(height):
        dest = ((top + y) * WIDTH + left) * 4
        start = y * width * 4
        canvas[dest:dest + width * 4] = pixels[start:start + width * 4]
    return bytes(canvas)


def render(data, bias="center"):
    width, height, pixels = read_png(data)
    left, top, cropped_width, cropped_height = crop_rect(width, height, bias)
    pixels = crop(pixels, width, height, left, top, cropped_width, cropped_height)
    if cropped_width < WIDTH or cropped_height < HEIGHT:
        return write_png(letterbox(pixels, cropped_width, cropped_height), WIDTH, HEIGHT)
    return write_png(scale(pixels, cropped_width, cropped_height, WIDTH, HEIGHT), WIDTH, HEIGHT)


def captions():
    return "\n".join(f"{name}\n{caption}\n" for name, _, _, caption in SCREENS) + "\n"


def write(root=ROOT):
    folder = destination(root)
    folder.mkdir(parents=True, exist_ok=True)
    for name, source, bias, _ in SCREENS:
        (folder / name).write_bytes(render(source_path(root, source).read_bytes(), bias))
    (folder / "captions.txt").write_text(captions())
    verify(root)
    return folder


def verify(root=ROOT):
    folder = destination(root)
    for name, source, bias, _ in SCREENS:
        expected = render(source_path(root, source).read_bytes(), bias)
        actual = (folder / name).read_bytes()
        if actual != expected:
            raise ValueError("Marketplace screenshot is stale: " + name)
        width, height, _ = read_png(actual)
        if (width, height) != (WIDTH, HEIGHT):
            raise ValueError("Marketplace screenshot must be 1280x800: " + name)
    if (folder / "captions.txt").read_text() != captions():
        raise ValueError("Marketplace captions are stale")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    if args.verify:
        verify()
        print("Marketplace screenshots are 1280x800 and match the current Retina captures")
        return
    folder = write()
    print("Wrote " + folder.relative_to(ROOT).as_posix())
    print(captions(), end="")


if __name__ == "__main__":
    main()
