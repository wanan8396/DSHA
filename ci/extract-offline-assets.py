#!/usr/bin/env python3
"""从官方发布的 APK 里提取离线资产，并重建 prepare-standard-assets 需要的一致性证书。

为什么需要它：offline-rootfs.bin / ubuntu-tools.bin 是构建产物、被 .gitignore 排除，
而 prepare-standard-assets.py 只接受「已有 rootfs + 匹配的 ubuntu-tools.inputs.json」。
这两个大文件在官方 APK 里是 STORED（noCompress bin），可以原样取出；
inputs 证书里的三项都能从取出的字节直接反算，不需要重新下载任何 apt 包：

  inputs           = prepare-ubuntu-tools.py 与 packages.lock.json 的 sha256
  archive_sha256   = 取出的 ubuntu-tools.bin 的 sha256
  base_status_sha256 = offline-rootfs.bin 内 var/lib/dpkg/status 的 sha256

用法：python3 ci/extract-offline-assets.py --apk <apk> --assets app/src/main/assets
"""
import argparse
import hashlib
import importlib.util
import json
import shutil
import sys
import tarfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

MEMBERS = ("assets/offline-rootfs.bin", "assets/ubuntu-tools.bin")


def digest(path):
    value = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            value.update(block)
    return value.hexdigest()


def load_tools_inputs():
    spec = importlib.util.spec_from_file_location(
        "ubuntu_tools_builder", ROOT / "tools" / "prepare-ubuntu-tools.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.inputs()


def base_status_sha256(rootfs):
    with tarfile.open(rootfs, "r:gz") as archive:
        member = next(
            item
            for item in archive
            if item.isfile() and item.name.removeprefix("./") == "var/lib/dpkg/status"
        )
        return hashlib.sha256(archive.extractfile(member).read()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    args = parser.parse_args()
    assets = args.assets.resolve()
    assets.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(args.apk) as bundle:
        names = set(bundle.namelist())
        missing = [name for name in MEMBERS if name not in names]
        if missing:
            raise SystemExit("APK 缺少离线资产: " + ", ".join(missing))
        for name in MEMBERS:
            target = assets / Path(name).name
            with bundle.open(name) as source, target.open("wb") as sink:
                shutil.copyfileobj(source, sink, 1 << 20)
            print(f"extracted {name} -> {target} ({target.stat().st_size} bytes)")

    metadata = {
        "inputs": load_tools_inputs(),
        "archive_sha256": digest(assets / "ubuntu-tools.bin"),
        "base_status_sha256": base_status_sha256(assets / "offline-rootfs.bin"),
    }
    target = assets / "ubuntu-tools.inputs.json"
    target.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("wrote", target)
    return 0


if __name__ == "__main__":
    sys.exit(main())
