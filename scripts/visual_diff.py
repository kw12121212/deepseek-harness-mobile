#!/usr/bin/env python3
"""Pixel-diff two screenshots for visual-regression checks.

Usage:
    python3 scripts/visual_diff.py BEFORE AFTER [--out diff.png]
                                   [--threshold 0.5] [--ignore-top N]

Exit codes: 0 = under threshold, 1 = over threshold or size mismatch, 2 = usage/IO error.

Needs Pillow; numpy is used when present. The DSH bundled interpreter already has both:
    ~/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/bin/python3
"""
import argparse
import sys


def main() -> int:
    parser = argparse.ArgumentParser(description="Pixel-diff two screenshots.")
    parser.add_argument("before")
    parser.add_argument("after")
    parser.add_argument("--out", default=None, help="write a highlighted diff image here")
    parser.add_argument("--threshold", type=float, default=0.5,
                        help="max percentage of differing pixels before failing (default 0.5)")
    parser.add_argument("--ignore-top", type=int, default=0,
                        help="skip this many rows at the top (status bar clock)")
    args = parser.parse_args()

    try:
        from PIL import Image
    except ImportError:
        print("Pillow is missing: python3 -m pip install pillow", file=sys.stderr)
        return 2

    try:
        before = Image.open(args.before).convert("RGB")
        after = Image.open(args.after).convert("RGB")
    except OSError as error:
        print(f"cannot read image: {error}", file=sys.stderr)
        return 2

    if before.size != after.size:
        print(f"size mismatch: {before.size} vs {after.size}", file=sys.stderr)
        return 1

    if args.ignore_top:
        box = (0, args.ignore_top, before.size[0], before.size[1])
        before = before.crop(box)
        after = after.crop(box)

    try:
        import numpy
    except ImportError:
        numpy = None

    if numpy is not None:
        left = numpy.asarray(before, dtype=numpy.int16)
        right = numpy.asarray(after, dtype=numpy.int16)
        mask = numpy.any(left != right, axis=2)
        changed = int(mask.sum())
        diff = None
        if args.out:
            from PIL import ImageChops
            diff = ImageChops.difference(before, after)
    else:
        from PIL import ImageChops
        diff = ImageChops.difference(before, after)
        changed = sum(1 for pixel in diff.getdata() if any(pixel))
        if args.out:
            diff.save(args.out)

    total = before.size[0] * before.size[1]
    percent = 100.0 * changed / total

    if args.out and diff is not None:
        diff.save(args.out)

    print(f"changed={changed}/{total} ({percent:.3f}%) threshold={args.threshold}%")
    return 0 if percent <= args.threshold else 1


if __name__ == "__main__":
    raise SystemExit(main())
