"""Spike S4: reads the pixel errors off the s4- screenshots of a gametest run.

    .venv/bin/python client-fabric/gametest-env/s4_measure.py <run OUT dir> [<sheet dir>]

Reads the "S4 <name> cursor x,y ..." lines of synaxis-gametest/report.txt and, for each, the
screenshot screenshots/NNNN_<name>.png:

- a marker shot (magenta, or red for the "-tick" pan shots): the offset of the marker's centroid
  from the cursor;
- a "-face" shot: on how many of the cursor's four sides a straight walk from it, the cursor
  pixel included, meets the yellow fill of the hit face (4: the cursor is on the face).

With a sheet dir it also writes s4-sheet-still.png and s4-sheet-pan.png there: 120x120 crops around
the cursor at 2x. The still tiles are the face shot with the marker shot's magenta pixels pasted
over it; the pan tiles are the marker and "-tick" shots side by side. The cyan cross at the cursor is
drawn by this script: the game draws no cursor into its framebuffer.
"""

import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

LINE = re.compile(r"^S4 (\S+) cursor ([\d.]+),([\d.]+) partial_tick ([\d.]+) .* hit (\S+) tick_hit (\S+) uncentred_shift_px (\S+)$")
CROP = 120
ZOOM = 2


def masks(img):
    rgb = np.asarray(img)
    r, g, b = (rgb[..., i].astype(int) for i in range(3))
    return {
        "magenta": (r > 200) & (g < 80) & (b > 200),
        "red": (r > 200) & (g < 60) & (b < 60),
        "yellow": (r > 200) & (g > 200) & (b < 80),
    }


def offset(mask, cx, cy):
    ys, xs = np.nonzero(mask)
    if len(xs) == 0:
        return None
    dx, dy = xs.mean() + 0.5 - cx, ys.mean() + 0.5 - cy
    return dx, dy, float(np.hypot(dx, dy))


def face_sides(yellow, x, y, reach=400):
    h, w = yellow.shape
    sides = 0
    for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        for step in range(reach):
            px, py = x + dx * step, y + dy * step
            if not (0 <= px < w and 0 <= py < h):
                break
            if yellow[py, px]:
                sides += 1
                break
    return sides


def crop(img, cx, cy):
    half = CROP // 2
    tile = Image.new("RGB", (CROP, CROP))
    tile.paste(img.crop((cx - half, cy - half, cx + half, cy + half)), (0, 0))
    tile = tile.resize((CROP * ZOOM, CROP * ZOOM), Image.NEAREST)
    d = ImageDraw.Draw(tile)
    c = half * ZOOM
    for a, b in ((-24, -8), (8, 24)):
        d.line([(c + a, c), (c + b, c)], fill=(0, 255, 255), width=2)
        d.line([(c, c + a), (c, c + b)], fill=(0, 255, 255), width=2)
    return tile


def sheet(tiles, columns, path):
    rows = (len(tiles) + columns - 1) // columns
    size = CROP * ZOOM
    out = Image.new("RGB", (columns * size + (columns - 1) * 4, rows * size + (rows - 1) * 4), (255, 255, 255))
    for i, tile in enumerate(tiles):
        out.paste(tile, ((i % columns) * (size + 4), (i // columns) * (size + 4)))
    out.save(path)


def fmt(o):
    return "no marker" if o is None else "%+.1f, %+.1f, %.1f" % o


def main():
    run = Path(sys.argv[1])
    sheets = Path(sys.argv[2]) if len(sys.argv) > 2 else None
    shots = {re.sub(r"^\d{4}_", "", p.stem): p for p in (run / "screenshots").glob("*.png")}
    lines = {}
    for line in (run / "synaxis-gametest" / "report.txt").read_text().splitlines():
        m = LINE.match(line)
        if m:
            lines[m.group(1)] = m.groups()

    still, pan = [], []
    print("| shot | cursor | hit block | marker offset px (dx, dy, abs) | face sides around cursor |")
    print("|---|---|---|---|---|")
    for name, (_, cx, cy, _, hit, _, _) in lines.items():
        if name.startswith("s4-pan-") or name.endswith("-face"):
            continue
        cx, cy = float(cx), float(cy)
        marker_img = Image.open(shots[name]).convert("RGB")
        face_img = Image.open(shots[name + "-face"]).convert("RGB")
        magenta = masks(marker_img)["magenta"]
        sides = face_sides(masks(face_img)["yellow"], int(cx), int(cy))
        print(f"| {name} | {cx:.0f},{cy:.0f} | {hit.split('@')[0]} | {fmt(offset(magenta, cx, cy))} | {sides}/4 |")
        composite = face_img.copy()
        composite.paste(marker_img, (0, 0), Image.fromarray((magenta * 255).astype(np.uint8)))
        still.append(crop(composite, int(cx), int(cy)))

    print()
    print("| shot | partial tick | frame marker offset px | tick-position marker offset px |")
    print("|---|---|---|---|")
    for name, (_, cx, cy, partial_tick, _, _, _) in lines.items():
        if not name.startswith("s4-pan-") or name.endswith("-tick"):
            continue
        cx, cy = float(cx), float(cy)
        frame_img = Image.open(shots[name]).convert("RGB")
        tick_img = Image.open(shots[name + "-tick"]).convert("RGB")
        frame = offset(masks(frame_img)["magenta"], cx, cy)
        tick = offset(masks(tick_img)["red"], cx, cy)
        print(f"| {name} | {partial_tick} | {fmt(frame)} | {fmt(tick)} |")
        pan += [crop(frame_img, int(cx), int(cy)), crop(tick_img, int(cx), int(cy))]

    if sheets:
        sheet(still, 5, sheets / "s4-sheet-still.png")
        sheet(pan, 6, sheets / "s4-sheet-pan.png")


if __name__ == "__main__":
    main()
