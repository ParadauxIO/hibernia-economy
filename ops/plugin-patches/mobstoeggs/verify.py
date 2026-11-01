#!/usr/bin/env python3
"""Verify the failure and repair using the server's Adventure 5.2 libraries."""
from pathlib import Path
import subprocess
import sys

libraries, original, patched, output = map(Path, sys.argv[1:])
output.mkdir(parents=True, exist_ok=True)
jars = sorted(libraries.glob("net/kyori/*/5.2.0/*.jar"))
jars += [libraries / name for name in (
    "net/kyori/option/1.1.0/option-1.1.0.jar",
    "org/jetbrains/kotlin/kotlin-stdlib/2.2.21/kotlin-stdlib-2.2.21.jar",
    "org/jetbrains/annotations/26.0.2/annotations-26.0.2.jar",
)]
assert jars and all(p.is_file() for p in jars)
classpath = ":".join(map(str, jars))
subprocess.run(["javac", "-cp", classpath + ":" + str(original), "-d", str(output),
                str(Path(__file__).with_name("FormatProbe.java"))], check=True)
for label, jar in (("baseline", original), ("patched", patched)):
    result = subprocess.run(["java", "-cp", f"{classpath}:{jar}:{output}", "FormatProbe"],
                            capture_output=True, text=True)
    if label == "baseline":
        assert result.returncode != 0 and "NoSuchMethodError" in result.stderr and "LegacyComponentSerializer.toBuilder()" in result.stderr, result.stderr
        print("BASELINE: reproduced the production toBuilder linkage failure")
    else:
        assert result.returncode == 0, result.stdout + result.stderr
        print(result.stdout.strip())
