#!/usr/bin/env python3
"""校验 P0 APK、Git 与成功 JUnit 结果的来源一致性，不执行模型或设备操作。

只使用 Python 标准库。元数据一致性用于防止旧产物误标，不提供抗篡改的构建证明。
"""

import argparse
import hashlib
import io
import json
import re
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


class VerificationError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def json_object(data):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, f"JSON 重复字段：{key}")
            result[key] = value
        return result
    result = json.loads(data.decode("utf-8"), object_pairs_hook=unique)
    require(isinstance(result, dict), "JSON 顶层必须是对象")
    return result


def git_state(root):
    def git(*args):
        return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()
    commit = git("rev-parse", "--verify", "HEAD")
    require(re.fullmatch(r"[0-9a-f]{40,64}", commit), "Git HEAD 无效")
    return {"sourceCommit": commit,
            "sourceTreeClean": not git("status", "--porcelain=v1", "--untracked-files=all")}


def require_source(record, expected, label):
    require(record.get("sourceCommit") == expected["sourceCommit"], f"{label} 提交号不匹配")
    require(record.get("sourceTreeClean") is True, f"{label} 来自未提交或缺失 clean 标记的工作树")


class Binary:
    def __init__(self, data):
        self.data = data

    def take(self, offset, size):
        require(0 <= offset <= len(self.data) and 0 <= size <= len(self.data) - offset,
                "二进制字段越界")
        return self.data[offset:offset + size]

    def u16(self, offset):
        return struct.unpack("<H", self.take(offset, 2))[0]

    def u32(self, offset):
        return struct.unpack("<I", self.take(offset, 4))[0]

    def uleb(self, offset):
        value = 0
        for shift in range(0, 35, 7):
            byte = self.take(offset, 1)[0]
            offset += 1
            require(shift != 28 or byte <= 0x0f, "ULEB128 超出 32 位")
            value |= (byte & 0x7f) << shift
            if not byte & 0x80:
                return value, offset
        raise VerificationError("ULEB128 未终止")


def manifest_identity(data):
    """读取 Android binary XML 的 manifest package/version，不依赖本机 aapt。"""
    binary = Binary(data)
    require(binary.u16(0) == 0x0003 and binary.u32(4) == len(data), "APK manifest 不是完整 binary XML")
    strings = None
    identity = None
    offset = binary.u16(2)
    while offset < len(data):
        kind, header_size, size = struct.unpack("<HHI", binary.take(offset, 8))
        require(size >= header_size >= 8 and offset + size <= len(data), "manifest chunk 长度无效")
        chunk = Binary(binary.take(offset, size))
        if kind == 0x0001:
            require(strings is None and header_size >= 28, "manifest 字符池重复或无效")
            count, flags, start = chunk.u32(8), chunk.u32(16), chunk.u32(20)
            require(count <= size // 4 and header_size + count * 4 <= size, "manifest 字符池越界")
            strings = []
            for index in range(count):
                position = start + chunk.u32(header_size + index * 4)
                if flags & 0x100:
                    def length8(at):
                        value = chunk.take(at, 1)[0]
                        if value & 0x80:
                            return ((value & 0x7f) << 8) | chunk.take(at + 1, 1)[0], at + 2
                        return value, at + 1
                    _, position = length8(position)
                    length, position = length8(position)
                    value = chunk.take(position, length).decode("utf-8")
                    require(chunk.take(position + length, 1) == b"\0", "manifest 字符串未终止")
                else:
                    length = chunk.u16(position)
                    position += 2
                    if length & 0x8000:
                        length = ((length & 0x7fff) << 16) | chunk.u16(position)
                        position += 2
                    value = chunk.take(position, length * 2).decode("utf-16le")
                    require(chunk.take(position + length * 2, 2) == b"\0\0", "manifest 字符串未终止")
                strings.append(value)
        elif kind == 0x0102:
            require(strings is not None and header_size == 16, "manifest start element 无效")
            name_index = chunk.u32(20)
            require(name_index < len(strings), "manifest element 名称越界")
            if strings[name_index] == "manifest":
                require(identity is None, "manifest 根元素重复")
                identity = {}
                first, stride, count = chunk.u16(24), chunk.u16(26), chunk.u16(28)
                require(stride >= 20, "manifest attribute 长度无效")
                for index in range(count):
                    at = 16 + first + index * stride
                    name_index = chunk.u32(at + 4)
                    require(name_index < len(strings), "manifest attribute 名称越界")
                    name = strings[name_index]
                    if name not in ("package", "versionCode", "versionName"):
                        continue
                    require(name not in identity, f"manifest {name} 重复")
                    value_type, value = chunk.take(at + 15, 1)[0], chunk.u32(at + 16)
                    if value_type == 0x03:
                        require(value < len(strings), "manifest attribute 字符串越界")
                        value = strings[value]
                    else:
                        require(value_type == 0x10, f"manifest {name} 不是字符串或整数")
                    identity[name] = value
        offset += size
    require(identity is not None, "manifest 缺少根元素")
    return identity


class Dex(Binary):
    """只读取未混淆 BuildConfig 静态常量；未知 DEX 格式直接拒绝。"""
    def __init__(self, data):
        super().__init__(data)
        require(self.take(0, 8) in (b"dex\n035\0", b"dex\n037\0", b"dex\n038\0", b"dex\n039\0", b"dex\n040\0"),
                "DEX 版本不受来源验证器支持")
        require(self.u32(32) == len(data) and self.u32(36) == 112
                and self.u32(40) == 0x12345678, "DEX header 无效")
        self.strings_count, self.strings_offset = self.u32(56), self.u32(60)
        self.types_count, self.types_offset = self.u32(64), self.u32(68)
        self.fields_count, self.fields_offset = self.u32(80), self.u32(84)
        self.classes_count, self.classes_offset = self.u32(96), self.u32(100)
        for count, offset, width in ((self.strings_count, self.strings_offset, 4),
                                     (self.types_count, self.types_offset, 4),
                                     (self.fields_count, self.fields_offset, 8),
                                     (self.classes_count, self.classes_offset, 32)):
            self.take(offset, count * width)

    def string(self, index):
        require(index < self.strings_count, "DEX string index 越界")
        offset = self.u32(self.strings_offset + index * 4)
        length, start = self.uleb(offset)
        end = self.data.find(b"\0", start)
        require(end >= start, "DEX string 未终止")
        value = self.take(start, end - start)
        # 所需 descriptor、字段名和当前版本 / commit 都是 ASCII，无需误用 UTF-8 解码 MUTF-8。
        require(len(value) == length and all(byte < 128 for byte in value), "来源字段必须是 ASCII MUTF-8")
        return value.decode("ascii")

    def type_name(self, index):
        require(index < self.types_count, "DEX type index 越界")
        return self.string(self.u32(self.types_offset + index * 4))

    def encoded_value(self, offset):
        header = self.take(offset, 1)[0]
        kind, argument = header & 0x1f, header >> 5
        offset += 1
        if kind == 0x1f:
            require(argument <= 1, "DEX BOOLEAN 无效")
            return bool(argument), offset
        if kind == 0x1e:
            require(argument == 0, "DEX NULL 无效")
            return None, offset
        widths = {0x00: 1, 0x02: 2, 0x03: 2, 0x04: 4, 0x06: 8,
                  0x10: 4, 0x11: 8, 0x17: 4, 0x18: 4, 0x19: 4, 0x1a: 4, 0x1b: 4}
        require(kind in widths and argument + 1 <= widths[kind], "DEX 静态常量类型无效或不支持")
        size = argument + 1
        value = int.from_bytes(self.take(offset, size), "little", signed=kind in (0x00, 0x02, 0x04, 0x06))
        if kind == 0x17:
            value = self.string(value)
        return value, offset + size

    def build_configs(self):
        result = []
        wanted = {"SOURCE_COMMIT": "Ljava/lang/String;", "SOURCE_TREE_CLEAN": "Z",
                  "APPLICATION_ID": "Ljava/lang/String;", "VERSION_CODE": "I",
                  "VERSION_NAME": "Ljava/lang/String;", "BUILD_TYPE": "Ljava/lang/String;"}
        for index in range(self.classes_count):
            at = self.classes_offset + index * 32
            class_index = self.u32(at)
            if self.type_name(class_index) != "Lio/xiyan/agent/BuildConfig;":
                continue
            offset, values_offset = self.u32(at + 24), self.u32(at + 28)
            require(offset > 0 and values_offset > 0, "BuildConfig 缺少静态字段 / 常量")
            count, offset = self.uleb(offset)
            for _ in range(3):
                _, offset = self.uleb(offset)
            require(count <= self.fields_count, "BuildConfig static field 数量越界")
            fields, field_index = [], 0
            for _ in range(count):
                delta, offset = self.uleb(offset)
                flags, offset = self.uleb(offset)
                field_index += delta
                require(field_index < self.fields_count, "DEX field index 越界")
                field_at = self.fields_offset + field_index * 8
                owner, type_index = self.u16(field_at), self.u16(field_at + 2)
                name = self.string(self.u32(field_at + 4))
                require(owner == class_index, "BuildConfig 字段所属类不匹配")
                if name in wanted:
                    require(self.type_name(type_index) == wanted[name] and flags & 0x19 == 0x19,
                            f"BuildConfig.{name} 类型或访问标志不匹配")
                fields.append(name)
            count, offset = self.uleb(values_offset)
            require(count <= len(fields), "DEX static_values 数量超过字段数")
            values = {}
            for position in range(count):
                value, offset = self.encoded_value(offset)
                if fields[position] in wanted:
                    require(fields[position] not in values, "BuildConfig 字段重复")
                    values[fields[position]] = value
            require(set(values) == set(wanted), "BuildConfig 来源或版本字段缺失")
            result.append(values)
        return result


def verify_apk(apk_bytes, expected):
    with zipfile.ZipFile(io.BytesIO(apk_bytes)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "APK ZIP entry 重复")
        require("assets/xiyan-build.json" in names, "APK 缺少 assets/xiyan-build.json，请重新构建")
        require(archive.getinfo("assets/xiyan-build.json").file_size <= 65536, "APK build metadata 过大")
        info = json_object(archive.read("assets/xiyan-build.json"))
        require(info.get("schemaVersion") == 1, "APK build metadata schema 不受支持")
        require_source(info, expected, "APK build metadata")
        require(info.get("applicationId") == "io.xiyan.agent.p0" and info.get("buildType") == "debug",
                "只允许 io.xiyan.agent.p0 的 debug 技术验证包")
        require(type(info.get("versionCode")) is int and info["versionCode"] > 0, "APK versionCode 无效")
        require(isinstance(info.get("versionName"), str) and re.fullmatch(r"[0-9A-Za-z.+-]+", info["versionName"]),
                "APK versionName 无效")
        manifest = manifest_identity(archive.read("AndroidManifest.xml"))
        require(manifest == {"package": info["applicationId"], "versionCode": info["versionCode"],
                             "versionName": info["versionName"]}, "APK manifest 与嵌入元数据版本 / 包名不一致")
        configs = []
        dex_names = [name for name in names if re.fullmatch(r"classes(?:[0-9]+)?\.dex", name)]
        require("classes.dex" in dex_names, "APK 缺少 classes.dex")
        for name in dex_names:
            configs.extend(Dex(archive.read(name)).build_configs())
        require(len(configs) == 1, "APK 必须包含唯一的实际 DEX BuildConfig 类")
        require(configs[0].get("SOURCE_TREE_CLEAN") is True and type(configs[0].get("VERSION_CODE")) is int,
                "实际 DEX BuildConfig 的 clean 或版本字段类型无效")
        require(configs[0] == {"SOURCE_COMMIT": info["sourceCommit"], "SOURCE_TREE_CLEAN": True,
                               "APPLICATION_ID": info["applicationId"], "VERSION_CODE": info["versionCode"],
                               "VERSION_NAME": info["versionName"], "BUILD_TYPE": info["buildType"]},
                "实际 DEX BuildConfig 字段与 APK 嵌入元数据不一致")
    return info, manifest


def verify_junit(root, module, task_name, expected):
    directory = root / module / "build" / "test-results" / task_name
    provenance_path = directory / "xiyan-provenance.json"
    require(provenance_path.is_file(), f"缺少 {module} JUnit provenance，请重跑对应测试 task")
    provenance_bytes = provenance_path.read_bytes()
    provenance = json_object(provenance_bytes)
    require(provenance.get("schemaVersion") == 1, f"{module} JUnit provenance schema 无效")
    require_source(provenance, expected, f"{module} JUnit provenance")
    require(provenance.get("testTask") == f":{module}:{task_name}", f"{module} JUnit task 不匹配")
    records = provenance.get("results")
    require(isinstance(records, list) and records, f"{module} provenance 没有测试结果")
    files, summaries, passed = {}, [], 0
    for row in records:
        require(isinstance(row, dict), f"{module} 测试记录无效")
        name = row.get("file")
        require(isinstance(name, str) and re.fullmatch(r"TEST-[^/\\]+\.xml", name), "JUnit 文件名无效")
        require(name not in files, "JUnit provenance 重复文件")
        path = directory / name
        require(path.is_file(), f"缺少 JUnit 文件：{path}")
        data = path.read_bytes()
        require(sha256(data) == row.get("sha256"), f"JUnit XML 已改变：{module}/{name}")
        suite = ET.fromstring(data)
        require(suite.tag == "testsuite", "JUnit 顶层不是 testsuite")
        counts = {}
        for key in ("tests", "failures", "errors", "skipped"):
            require(type(row.get(key)) is int and row[key] >= 0, "JUnit provenance 数量无效")
            counts[key] = int(suite.attrib[key])
            require(counts[key] == row[key], "JUnit XML 与 provenance 数量不同")
        require(counts["failures"] == 0 and counts["errors"] == 0, f"{module} JUnit 存在失败")
        cases = suite.findall("testcase")
        require(len(cases) == counts["tests"] and sum(case.find("skipped") is not None for case in cases) == counts["skipped"],
                "JUnit testcase 数量与汇总不匹配")
        require(all(case.find("failure") is None and case.find("error") is None for case in cases), "JUnit testcase 存在失败")
        passed += counts["tests"] - counts["skipped"]
        files[name] = data
        summaries.append({"name": suite.attrib.get("name"), **counts, "sha256": row["sha256"]})
    require(set(files) == {path.name for path in directory.glob("TEST-*.xml")}, f"{module} JUnit 文件集合与 provenance 不一致")
    require(passed > 0, f"{module} 没有实际通过的测试")
    files["xiyan-provenance.json"] = provenance_bytes
    return summaries, files


def verify(root, apk):
    expected = git_state(root)
    require(expected["sourceTreeClean"], "工作树有未提交修改或未跟踪文件，正式打包必须来自 clean commit")
    require(apk.is_file() and apk.stat().st_size <= 512 * 1024 * 1024, "APK 缺失或超过 P0 大小限制")
    apk_bytes = apk.read_bytes()
    embedded, manifest = verify_apk(apk_bytes, expected)
    core, core_files = verify_junit(root, "core", "test", expected)
    android, android_files = verify_junit(root, "app", "testDebugUnitTest", expected)
    require(git_state(root) == expected, "校验期间 Git HEAD 或工作树状态已改变")
    report = {
        "stage": "P0 technical preview; acceptance pending",
        "sourceCommit": embedded["sourceCommit"], "sourceTreeClean": True,
        "applicationId": embedded["applicationId"], "version": embedded["versionName"],
        "versionCode": embedded["versionCode"], "buildType": embedded["buildType"],
        "apkSha256": sha256(apk_bytes), "embeddedBuild": embedded, "manifest": manifest,
        "sourceVerification": "Git clean HEAD, embedded asset, actual DEX BuildConfig, and hash-bound JUnit provenance agree; not a tamper-proof build attestation",
        "signature": "See APK-SIGNATURE.txt for verified certificate details; this is not production signing acceptance",
        "coreUnitTests": core, "androidUnitTests": android,
        "androidUnitTestsKind": "Local JVM unit tests, not Android device or instrumentation tests",
        "unitTestTotals": {key: sum(row[key] for row in core + android) for key in ("tests", "failures", "errors", "skipped")},
        "accountLoginTested": False, "realInferenceTested": False, "iqooPhysicalDeviceTested": False,
        "longDurationTests": "30/60/120 minutes not performed",
    }
    return report, {"core": core_files, "app": android_files}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--evidence-dir", type=Path)
    parser.add_argument("--check-source-only", type=Path, metavar="VERIFIED_REPORT")
    args = parser.parse_args()
    try:
        root = args.root.resolve()
        if args.check_source_only:
            report = json_object(args.check_source_only.read_bytes())
            current = git_state(root)
            require(current["sourceTreeClean"], "发布前工作树已经改变")
            require_source(report, current, "已验证报告")
            return 0
        apk = args.apk or root / "app/build/outputs/apk/debug/app-debug.apk"
        report, evidence = verify(root, apk)
        # 只在全部校验成功后写临时报告及同一批原始 XML bytes。
        if args.evidence_dir:
            for module, files in evidence.items():
                directory = args.evidence_dir / module
                directory.mkdir(parents=True, exist_ok=True)
                for name, data in files.items():
                    (directory / name).write_bytes(data)
        if args.report:
            args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"sourceCommit": report["sourceCommit"], "sourceTreeClean": True,
                          "applicationId": report["applicationId"], "version": report["version"],
                          "unitTests": report["unitTestTotals"]}, ensure_ascii=False))
        return 0
    except (VerificationError, OSError, ValueError, KeyError, struct.error, zipfile.BadZipFile,
            ET.ParseError, subprocess.CalledProcessError) as failure:
        print(f"P0 来源校验失败：{failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
