#!/usr/bin/env python3
from pathlib import Path
import subprocess
import os
import sys
import zipfile

server, work = map(Path, sys.argv[1:])
root = Path(__file__).resolve().parent.parent
work.mkdir(parents=True, exist_ok=True)
classes = work/'classes'
classes.mkdir(exist_ok=True)
libs = server/'libraries'
jars = list(libs.glob('net/kyori/*/5.2.0/*.jar'))
jars += list(libs.glob('io/papermc/paper/paper-api/26.3.build.206-stable/*.jar'))
jars += list(libs.glob('org/jetbrains/annotations/26.0.2/*.jar'))
jars += list(libs.glob('com/google/guava/guava/*/*.jar'))
jars += list(libs.glob('org/slf4j/slf4j-api/2.0.17/*.jar'))
jars += [server/'plugins/BountyHunters-2.6.2.jar']
java = Path(os.environ['JAVA_HOME']) / 'bin'
sources = [root/'staging-verifier/StagingCompatProbe.java', root/'carbon-message-delete/LiveCarbonProbe.java', root/'musicbox/LiveMusicBoxProbe.java', root/'families/LiveFamiliesProbe.java', root/'bountyhunters/LiveBountyProbe.java']
subprocess.run([str(java/'javac'), '-cp', ':'.join(map(str,jars)), '-d', str(classes), *map(str,sources)], check=True)
with zipfile.ZipFile(work/'StagingCompatProbe.jar', 'w', zipfile.ZIP_DEFLATED) as jar:
    jar.write(root/'staging-verifier/paper-plugin.yml', 'paper-plugin.yml')
    for path in sorted(classes.rglob('*.class')): jar.write(path, path.relative_to(classes))
print(work/'StagingCompatProbe.jar')
