#!/usr/bin/env python3
"""Straight-develop GimbalBlur DNG files into the D modality (8-bit sRGB PNG).

LibRaw (rawpy) development with the camera white balance; every other parameter
stays at the rawpy default (AHD demosaicing, sRGB primaries, BT.709 tone curve,
automatic brightness, orientation from the DNG).

  # one file
  python develop_raw.py --dng blur_3_200_8.dng --out blur_3_200_8.png
  # a whole tree: <device>/scene_xxx/{gt_raw,blur_raw}/*.dng -> {gt_dev,blur_dev}/*.png
  python develop_raw.py --root GimbalBlur-Fullres [--out <other root>] [--workers 8]
"""
import argparse
from multiprocessing import get_context
from pathlib import Path

import cv2
import rawpy

RAW_TO_DEV = {"gt_raw": "gt_dev", "blur_raw": "blur_dev"}


def develop(dng_path, png_path):
    with rawpy.imread(str(dng_path)) as raw:
        rgb = raw.postprocess(use_camera_wb=True, output_color=rawpy.ColorSpace.sRGB, output_bps=8)
    png_path.parent.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(png_path), cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR))
    print(png_path)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dng", type=Path, help="single DNG file")
    ap.add_argument("--root", type=Path, help="dataset root (tree mode)")
    ap.add_argument("--out", type=Path, help="output PNG (single file) or output root (tree mode, default: --root)")
    ap.add_argument("--workers", type=int, default=4)
    args = ap.parse_args()

    if args.dng:
        develop(args.dng, args.out or args.dng.with_suffix(".png"))
        return
    if not args.root:
        ap.error("give --dng or --root")
    out_root = args.out or args.root
    tasks = []
    for raw_sub, dev_sub in RAW_TO_DEV.items():
        for dng in sorted(args.root.glob(f"*/scene_*/{raw_sub}/*")):
            if dng.suffix.lower() == ".dng":
                rel = dng.relative_to(args.root)
                tasks.append((dng, out_root / rel.parent.parent / dev_sub / f"{dng.stem}.png"))
    with get_context("spawn").Pool(args.workers) as pool:  # rawpy uses OpenMP; avoid fork
        pool.starmap(develop, tasks)


if __name__ == "__main__":
    main()
