#!/usr/bin/env python3
"""Pads a screenshot to a Play Store-safe aspect ratio without cropping.

Play Console screenshot requirements effectively cap the aspect ratio at
2:1 (max side no more than 2x the min side), described in the upload form as
"16:9 or 9:16". Modern phones are commonly 20:9 or 19.5:9, which fails that
check. Cropping to fit loses UI at the edges (status bar, nav bar, on-screen
controls) - padding with the screenshot's own background color instead keeps
every pixel of the original content and just adds letterbox/pillarbox bars.

Usage:
    python pad_to_store_ratio.py input.png output.png --orientation portrait
    python pad_to_store_ratio.py input.png output.png --orientation landscape
"""
import argparse
from PIL import Image

RATIOS = {"portrait": (9, 16), "landscape": (16, 9)}


def pad_to_ratio(path, out_path, target_w, target_h):
    img = Image.open(path).convert("RGB")
    w, h = img.size
    target_ratio = target_w / target_h
    current_ratio = w / h
    # Sample a corner pixel as the fill color, on the assumption it's part of
    # the app's background rather than mid-content - true for most player/
    # list screens but check the output on anything with edge-to-edge art.
    bg = img.getpixel((0, h - 1))
    if current_ratio < target_ratio:
        new_w = round(h * target_ratio)
        canvas = Image.new("RGB", (new_w, h), bg)
        canvas.paste(img, ((new_w - w) // 2, 0))
    else:
        new_h = round(w / target_ratio)
        canvas = Image.new("RGB", (w, new_h), bg)
        canvas.paste(img, (0, (new_h - h) // 2))
    canvas.save(out_path, "PNG")
    print(f"{path}: {img.size} -> {canvas.size} (bg={bg})")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input")
    parser.add_argument("output")
    parser.add_argument("--orientation", choices=RATIOS, required=True)
    args = parser.parse_args()
    tw, th = RATIOS[args.orientation]
    pad_to_ratio(args.input, args.output, tw, th)
