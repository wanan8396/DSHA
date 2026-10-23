#!/usr/bin/env python3
"""独立运行时描述：APK UI 版本仅作诊断，不参与受管运行时身份。"""
from source_text import write_text as write_source_text, matches_text
import hashlib
import json
from pathlib import Path
import sys
from runtime_input_contract import load, asset_paths, launcher_paths, bridge_protocol

ROOT = Path(__file__).resolve().parents[1]

# The descriptor uses explicit data-format evidence rather than deriving
# compatibility from a version number.  DSH 0.2.0-rc.2 keeps the same session
# and JSON storage readers as the shipped 0.1.7-rc.2 runtime (both runtimes
# expose the v4 session catalog and identical storage-json implementation), so
# an upgrade may read the previous runtime's records.  Keep this list narrow:
# a future runtime must add a reviewed entry here after comparing its readers.
READ_COMPATIBILITY = {
    '0.2.0-rc.2': ['dsh-0.1.7-rc.1', 'dsh-0.1.7-rc.2'],
    # 0.2.1-rc.2 仍读写同一套 session catalog 与 storage-json，并额外发布了显式的
    # dsh-session-format-v0-to-v1 … v3-to-v4 迁移包（读取器带迁移链），因此可以读取
    # 0.2.0-rc.2 写出的记录。**注意**：这一条来自包结构证据，尚未在真机上做
    # 「0.2.0 写 → 0.2.1 读」的实测；设备验收必须单独确认老会话可正常打开。
    '0.2.1-rc.2': ['dsh-0.1.7-rc.1', 'dsh-0.1.7-rc.2', 'dsh-0.2.0-rc.2'],
}

def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(data)
    return value.hexdigest()

def build_descriptor(root):
    root = Path(root)
    assets = root / 'app/src/main/assets'
    spec = load(root)
    inputs = {p.relative_to(assets).as_posix(): digest(p) for p in asset_paths(root, spec)}
    launchers = {name: digest(path) for name, path in launcher_paths(root, spec).items()}
    version = json.loads((root / 'tools/dsh-runtime/package.json').read_text(encoding='utf8'))['dependencies']['@deepseek-ai/dsh']
    previous = READ_COMPATIBILITY.get(version, [])
    contract = {'baseVersion': (assets / 'offline-rootfs.version').read_text().strip(),
                'dshVersion': version, 'launcherContract': 'DSHA_ARM64_V2',
                'launcherInputs': launchers, 'bridgeProtocol': bridge_protocol(root),
                'dataRead': previous + ['dsh-' + version], 'dataWrite': 'dsh-' + version,
                'inputs': inputs}
    identity = hashlib.sha256(json.dumps(contract, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    return dict(version=1, runtimeId=identity, **contract)

def main(args):
    output = build_descriptor(ROOT)
    target = ROOT / 'app/src/main/assets/runtime-descriptor.json'
    content = json.dumps(output, ensure_ascii=False, indent=2) + '\n'
    if args == ['--check']:
        if not matches_text(target,content,encoding='utf8'):
            raise SystemExit('运行时描述符与当前受管输入不一致；先运行 tools/prepare-runtime-descriptor.py --write 并审阅改动')
    elif args in ([], ['--write']):
        write_source_text(target,content,encoding='utf8')
    else:
        raise SystemExit('usage: prepare-runtime-descriptor.py [--check|--write]')
    print('runtime descriptor:', output['runtimeId'])

if __name__ == '__main__':
    main(sys.argv[1:])
