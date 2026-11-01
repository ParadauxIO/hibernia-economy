#!/usr/bin/env python3
"""Build and verify the narrowly hash-pinned BountyHunters patch from local libraries."""
from pathlib import Path
import subprocess
import hashlib
import os
import sys
import zipfile

server, work = map(Path, sys.argv[1:])
source = Path(__file__).resolve().parent
work.mkdir(parents=True, exist_ok=True)
(work/'mockito-extensions').mkdir(exist_ok=True)
(work/'mockito-extensions/org.mockito.plugins.MockMaker').write_text('mock-maker-subclass')
java = Path(os.environ['JAVA_HOME']) / 'bin'
libs = server / 'libraries'
asm = list(libs.glob('org/ow2/asm/*/9.9.1/*.jar'))
patchcp = ':'.join(map(str, asm))
subprocess.run([str(java/'javac'), '-cp', patchcp, '-d', str(work), str(source/'Patch.java')], check=True)
original = server/'plugins/BountyHunters-2.6.2.jar'
patched = work/'BountyHunters-2.6.2-navigation.jar'
assert hashlib.sha256(original.read_bytes()).hexdigest() == '7ae836d097cb48d5a4494948cc66c9847411b25ce23509af22b57f063ca361cf'
if patched.exists():
    assert hashlib.sha256(patched.read_bytes()).hexdigest() == 'df1d450c9ff76e6124051d185ed409063c76e9cce1f0d241dba037db6b307bee'
if not patched.exists():
    subprocess.run([str(java/'java'), '-cp', patchcp+':'+str(work), 'Patch', str(original), str(patched)], check=True)
cache = Path.home()/'.gradle/caches/modules-2/files-2.1'
dependencies = list(libs.glob('net/kyori/*/5.2.0/*.jar'))
for pattern in ['io/papermc/paper/paper-api/26.3.build.206-stable/*.jar', 'com/google/guava/guava/*/*.jar', 'com/google/code/gson/gson/*/*.jar', 'org/jetbrains/annotations/26.0.2/*.jar', 'org/yaml/snakeyaml/*/*.jar', 'org/slf4j/slf4j-api/2.0.17/*.jar', 'org/apache/logging/log4j/log4j-api/*/*.jar', 'net/kyori/option/1.1.0/*.jar', 'it/unimi/dsi/fastutil/*/*.jar']:
    dependencies += list(libs.glob(pattern))
for pattern in ['org.mockito/mockito-core/5.24.0/*/*.jar', 'net.bytebuddy/byte-buddy/1.18.11/*/*.jar', 'org.objenesis/objenesis/3.3/*/*.jar']:
    dependencies += list(cache.glob(pattern))
cp = ':'.join(map(str, dependencies))
subprocess.run([str(java/'javac'), '-cp', cp+':'+str(original), '-d', str(work), str(source/'Probe.java')], check=True)
for name, artifact in [('baseline', original), ('patched', patched)]:
    result = subprocess.run([str(java/'java'), '-cp', cp+':'+str(artifact)+':'+str(work), 'Probe'], capture_output=True, text=True)
    (work/(name+'.log')).write_text(result.stdout+result.stderr)
    if name == 'baseline':
        assert result.returncode and 'NullPointerException' in result.stderr and 'Placeholders.<init>' in result.stderr, result.stderr
        print('BASELINE: reproduced null inventory in Placeholders constructor')
    else:
        assert result.returncode == 0, result.stdout+result.stderr
        print(result.stdout)
with zipfile.ZipFile(original) as a, zipfile.ZipFile(patched) as b:
    assert a.namelist() == b.namelist()
    changed = [n for n in a.namelist() if a.read(n) != b.read(n)]
    assert len(changed) == 4 and all(n.endswith(('NextPageItem.class', 'PreviousPageItem.class', 'GoBackItem.class', 'CloseInventoryItem.class')) for n in changed)
    print('Only changed:', changed)
