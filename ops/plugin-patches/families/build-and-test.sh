#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../../.." && pwd)
server=${1:?Usage: build-and-test.sh ORIGINAL_SERVER_DIR}
work="$root/.context/families"
source="$root/ops/plugin-patches/families"
asm="$server/libraries/org/ow2/asm/asm/9.9.1/asm-9.9.1.jar:$server/libraries/org/ow2/asm/asm-tree/9.9.1/asm-tree-9.9.1.jar"
mkdir -p "$work/classes" "$work/test-classes"
javac -cp "$asm" -d "$work/classes" "$source/SafeBackground.java" "$source/PatchFamilies.java"
javac -d "$work/test-classes" "$source/TestFamilies.java" "$source/TestConfig.java"
java -cp "$work/classes:$asm" PatchFamilies "$server/plugins/Families-1.0.3-SNAPSHOT.jar" "$work/Families-1.0.3-SNAPSHOT.jar" "$work/classes"
java -Xverify:all -cp "$server/plugins/Families-1.0.3-SNAPSHOT.jar:$work/test-classes:$server/plugins/packetevents-spigot-2.14.0.jar" TestFamilies baseline
java -Xverify:all -cp "$work/Families-1.0.3-SNAPSHOT.jar:$work/test-classes:$server/plugins/packetevents-spigot-2.14.0.jar" TestFamilies patched
cp="$work/Families-1.0.3-SNAPSHOT.jar:$work/test-classes"
for jar in "$server"/libraries/net/kyori/*/5.2.0/*.jar "$server"/libraries/net/kyori/examination-{api,string}/1.3.0/examination-{api,string}-1.3.0.jar; do
    [[ -f "$jar" ]] && cp="$cp:$jar"
done
java -Xverify:all -cp "$cp" TestConfig "$work/config-test"
sha256sum "$work/Families-1.0.3-SNAPSHOT.jar"
