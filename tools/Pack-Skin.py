"""Pack editable visual data. The Android importer performs final validation."""
import argparse
import json
import re
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser(description="Create a YMPlayer .ymskin ZIP")
parser.add_argument("directory", type=Path, help="Directory containing manifest.json")
parser.add_argument("output", type=Path, help="Destination .ymskin file")
args = parser.parse_args()
root = args.directory.resolve(strict=True)
manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
names = {"manifest.json", *manifest.get("icons", {}).values()}
if len(names) > 64:
    parser.error("Too many files")
files = []
for name in sorted(names):
    if not re.fullmatch(r"[A-Za-z0-9_./-]{1,160}", name) or any(part in ("", ".", "..") for part in name.split("/")):
        parser.error(f"Invalid package path: {name}")
    path = (root / name).resolve(strict=True)
    if not path.is_relative_to(root) or not path.is_file() or path.stat().st_size > 256 * 1024:
        parser.error(f"Invalid or oversized file: {name}")
    files.append((name, path.read_bytes()))
if sum(len(data) for _, data in files) > 2 * 1024 * 1024:
    parser.error("Too much uncompressed data")
args.output.parent.mkdir(parents=True, exist_ok=True)
# Stable timestamps make unchanged sources produce identical packages.
with zipfile.ZipFile(args.output, "w") as archive:
    for name, data in files:
        entry = zipfile.ZipInfo(name, (2000, 1, 1, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(entry, data)
if args.output.stat().st_size > 1024 * 1024:
    args.output.unlink()
    parser.error("Archive exceeds 1 MiB")
print(f"Created {args.output} ({args.output.stat().st_size} bytes). Validate through YMPlayer import before distributing.")
