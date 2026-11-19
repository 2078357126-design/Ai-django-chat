#!/usr/bin/env python3
"""按规则自动递增版本号。

规则：次版本只到 9，再升就进位到下一个整数。
    1.9 -> 2.0, 2.9 -> 3.0, 2.0 -> 2.1

同时更新 version.json 与 android/app/build.gradle 里的 versionCode / versionName。
用法：python3 bump_version.py
"""

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent
VERSION_FILE = ROOT / "version.json"
GRADLE_FILE = ROOT / "android" / "app" / "build.gradle"


def main():
    data = json.loads(VERSION_FILE.read_text(encoding="utf-8"))
    version_name = data["versionName"]
    major_text, _, minor_text = version_name.partition(".")
    major = int(major_text)
    minor = int(minor_text or 0)

    minor += 1
    if minor > 9:
        major += 1
        minor = 0

    new_name = "{}.{}".format(major, minor)
    new_code = int(data["versionCode"]) + 1

    data["versionName"] = new_name
    data["versionCode"] = new_code
    VERSION_FILE.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    gradle = GRADLE_FILE.read_text(encoding="utf-8")
    gradle = re.sub(r"versionCode \d+", "versionCode {}".format(new_code), gradle, count=1)
    gradle = re.sub(
        r"versionName '[^']*'", "versionName '{}'".format(new_name), gradle, count=1
    )
    GRADLE_FILE.write_text(gradle, encoding="utf-8")

    print("{} (versionCode {})".format(new_name, new_code))


if __name__ == "__main__":
    main()
