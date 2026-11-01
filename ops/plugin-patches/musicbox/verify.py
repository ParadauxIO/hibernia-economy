#!/usr/bin/env python3
"""Run the packaged-JAR probe against original and rebuilt MusicBox artifacts."""
import os
from pathlib import Path
import subprocess
import sys

source, original, patched, work = (Path(p).resolve() for p in sys.argv[1:])
work.mkdir(parents=True, exist_ok=True)
java = Path(os.environ['JAVA_HOME']) / 'bin'
probe = Path(__file__).resolve().parent
classpath_file = work / 'classpath.txt'
subprocess.run(['bash', str(source / 'gradlew'), '-p', str(source), '--offline',
                '--no-daemon', '-I', str(probe / 'probe-classpath.init.gradle'),
                '-Dprobe.classpath.file=' + str(classpath_file),
                ':plugin:writeProbeClasspath'], check=True)
classpath = classpath_file.read_text()
subprocess.run([str(java / 'javac'), '-cp', classpath, '-d', str(work),
                str(probe / 'PackagingProbe.java')], check=True)
for label, artifact in [('baseline', original), ('patched', patched)]:
    result = subprocess.run([str(java / 'java'), '-XX:+EnableDynamicAgentLoading',
                             '-cp', str(work) + ':' + classpath,
                             'PackagingProbe', str(artifact)], capture_output=True, text=True)
    (work / (label + '.log')).write_text(result.stdout + result.stderr)
    if label == 'baseline':
        assert result.returncode and 'NoClassDefFoundError' in result.stderr and 'BinaryTagIO' in result.stderr, result.stderr
        print('BASELINE: reproduced missing Adventure NBT')
    else:
        assert result.returncode == 0, result.stdout + result.stderr
        print(result.stdout.strip())
