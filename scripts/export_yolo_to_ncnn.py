#!/usr/bin/env python3
"""
Convert a YOLO detection model (YOLOv8n / YOLO11n / YOLOv12n) to the NCNN
format NAVI AI's native detector expects, and copy the result into
app/src/main/assets/models/.

This is the process that produced the yolo_model.ncnn.param /
yolo_model.ncnn.bin bundled in this repo (YOLOv12n, imgsz=320).

    PyTorch (.pt) --[ultralytics export format=ncnn, via PNNX]--> NCNN (.param/.bin)

Usage:
    pip install -r scripts/requirements.txt
    python scripts/export_yolo_to_ncnn.py --weights yolo12n.pt --imgsz 320

IMPORTANT -- read before re-exporting:
--imgsz must be a multiple of 32 (the model's stride). Whatever value you
pick becomes kModelInputSize in app/src/main/cpp/native_detector.cpp. The
native code does NOT read the model's input shape at runtime: ultralytics'
ncnn export bakes anchor-point and per-anchor stride constants (as
MemoryData layers) directly into the graph, sized for one specific
resolution. If you export at a different --imgsz you MUST update
kModelInputSize to match and rebuild, or every decoded box coordinate will
silently be wrong -- this is exactly the class of bug this script's
inspect_output_format() step exists to catch before it reaches the app.

Alternative manual path (useful if a model doesn't support the ultralytics
'ncnn' export target directly, e.g. a custom architecture):
    1. torch.onnx.export(..., opset_version=12, dynamic_axes=None)
    2. pnnx model.onnx inputshape=[1,3,{imgsz},{imgsz}]
       (pnnx: https://github.com/pnnx/pnnx -- produces model.ncnn.param/.bin)
    3. ncnnoptimize model.ncnn.param model.ncnn.bin model_opt.ncnn.param
       model_opt.ncnn.bin 0     (0 = keep fp32; use 65536 to also fold to fp16)
This script uses ultralytics' built-in 'ncnn' export target, which runs the
same PNNX conversion under the hood and is the supported, reliable path for
YOLOv8n/YOLO11n/YOLOv12n specifically.
"""

import argparse
import shutil
import sys
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--weights", required=True, help="Path to .pt weights (e.g. yolo12n.pt, yolov8n.pt, yolo11n.pt)")
    parser.add_argument("--imgsz", type=int, default=320, help="Square input resolution, multiple of 32 (default: 320)")
    parser.add_argument("--half", action="store_true", help="Export FP16 weights (smaller .bin; native side also enables fp16 arithmetic)")
    parser.add_argument("--assets-dir", default="app/src/main/assets/models", help="Where to copy the final .param/.bin (relative to repo root)")
    parser.add_argument("--skip-copy", action="store_true", help="Only export; don't overwrite the Android assets folder")
    args = parser.parse_args()

    if args.imgsz % 32 != 0:
        sys.exit(f"--imgsz must be a multiple of 32 (stride), got {args.imgsz}")

    try:
        from ultralytics import YOLO
    except ImportError:
        sys.exit("ultralytics is not installed. Run: pip install -r scripts/requirements.txt")

    weights_path = Path(args.weights)
    if not weights_path.exists():
        sys.exit(f"Weights file not found: {weights_path}")

    print(f"Loading {weights_path} ...")
    model = YOLO(str(weights_path))

    print(f"Exporting to NCNN: imgsz={args.imgsz}, half={args.half} ...")
    export_path = model.export(format="ncnn", imgsz=args.imgsz, half=args.half, simplify=True)
    export_dir = Path(export_path)
    if export_dir.is_file():
        export_dir = export_dir.parent

    param_file = next(export_dir.glob("*.ncnn.param"))
    bin_file = next(export_dir.glob("*.ncnn.bin"))
    print(f"Exported:\n  {param_file}\n  {bin_file}")

    inspect_output_format(param_file)

    if not args.skip_copy:
        assets_dir = Path(args.assets_dir)
        assets_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy(param_file, assets_dir / "yolo_model.ncnn.param")
        shutil.copy(bin_file, assets_dir / "yolo_model.ncnn.bin")
        write_coco_names(assets_dir / "coco.names")
        print(f"\nCopied model into {assets_dir}")
        if args.imgsz != 320:
            print(
                f"\n*** --imgsz={args.imgsz} != 320: update kModelInputSize in "
                "app/src/main/cpp/native_detector.cpp to match, then rebuild. ***"
            )


def inspect_output_format(param_file: Path) -> None:
    """
    Prints the graph's input/output layers and anchor-count contributors so
    the exported format is verified, not assumed -- this is what
    native_detector.cpp's decode logic (in NAVI AI's Android app) was
    written against: per anchor, [cx, cy, w, h, 80 sigmoid class scores],
    boxes already decoded to absolute pixel coordinates in the model's
    input space, with NO separate objectness channel. Re-run this after
    every re-export and diff against that expectation before touching the
    native decode code.
    """
    lines = param_file.read_text().splitlines()
    print("\n--- Graph inspection (verify, do not assume) ---")
    for line in lines:
        if line.startswith("Input"):
            print("Input layer   :", line)

    reshape_sizes = []
    for line in lines:
        if line.startswith("Reshape"):
            for token in line.split():
                if token.startswith("0="):
                    try:
                        reshape_sizes.append(int(token[2:]))
                    except ValueError:
                        pass
    print("Reshape 0= sizes (anchor-grid contributors, should sum to the total anchor count):", reshape_sizes)

    print("Final layers (expect the last line to be a Concat producing 'out0'):")
    for line in lines[-6:]:
        print(" ", line)


def write_coco_names(path: Path) -> None:
    names = [
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
        "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
        "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
        "toothbrush",
    ]
    assert len(names) == 80, f"expected 80 COCO class names, got {len(names)}"
    path.write_text("\n".join(names) + "\n")


if __name__ == "__main__":
    main()
