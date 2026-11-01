#!/usr/bin/env python3
"""Patch the known MobsToEggs 1.7.22 binary for Adventure 5's builder ABI."""
import hashlib
from pathlib import Path
import struct
import sys
import zipfile

SOURCE_SHA256 = "a4135b441af8c4b571d9b4dcfa5598c95a3fb73652d15151713324ac59fb7295"
TARGET = "fyi/sugar/mobstoeggs/utility/TextFormatManager.class"
OLD = b"()Lnet/kyori/adventure/util/Buildable$Builder;"
NEW = b"()Lnet/kyori/adventure/text/serializer/legacy/LegacyComponentSerializer$Builder;"


def patch(source, destination):
    source, destination = Path(source), Path(destination)
    if hashlib.sha256(source.read_bytes()).hexdigest() != SOURCE_SHA256:
        raise ValueError("Unexpected input JAR; inspect it before updating this patch")
    # A CONSTANT_Utf8 entry: tag, byte length, and descriptor. No instruction,
    # stack map, resource, or plugin metadata changes are required.
    before = b"\x01" + struct.pack(">H", len(OLD)) + OLD
    after = b"\x01" + struct.pack(">H", len(NEW)) + NEW
    with zipfile.ZipFile(source) as src:
        data = src.read(TARGET)
        if data.count(before) != 1:
            raise ValueError("Expected exactly one old builder descriptor")
        patched = data.replace(before, after)
        with destination.open("xb") as output, zipfile.ZipFile(output, "w") as dst:
            dst.comment = src.comment
            for entry in src.infolist():
                dst.writestr(entry, patched if entry.filename == TARGET else src.read(entry))
    with zipfile.ZipFile(source) as src, zipfile.ZipFile(destination) as dst:
        assert src.namelist() == dst.namelist()
        assert [n for n in src.namelist() if src.read(n) != dst.read(n)] == [TARGET]
    print(hashlib.sha256(destination.read_bytes()).hexdigest(), destination)


if __name__ == "__main__":
    patch(*sys.argv[1:])
