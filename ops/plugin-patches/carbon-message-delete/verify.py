#!/usr/bin/env python3
import os
import pathlib,subprocess,sys
server,original,patched,work=map(pathlib.Path,sys.argv[1:]);work.mkdir(parents=True,exist_ok=True)
java=pathlib.Path(os.environ['JAVA_HOME']) / 'bin'
libs=server/'libraries'
# Prefer Adventure 5.2; the remaining dependency jars only satisfy Carbon API type loading.
jars=sorted((p for p in libs.rglob('*.jar') if '/io/papermc/' not in str(p)),key=lambda p:(0 if '/5.2.0/' in str(p) else 1,str(p)))
cp=':'.join(map(str,[work,server/'plugins/carbonchat-paper-3.0.0-beta.39.jar',*jars]))
sources=list(pathlib.Path(__file__).with_name('probe').rglob('*.java'))
subprocess.run([str(java/'javac'),'-cp',cp,'-d',str(work),*map(str,sources)],check=True)
for name,jar in [('baseline',original),('patched',patched)]:
 r=subprocess.run([str(java/'java'),'-Xverify:all','-Dcarbon.probe.config='+str(server/'plugins/CarbonMessageDelete/config.yml'),'-Dnet.kyori.adventure.serviceLoadFailuresAreFatal=false','-cp',str(jar)+':'+cp,'Probe'],capture_output=True,text=True)
 (work/(name+'.log')).write_text(r.stdout+r.stderr)
 print(name,r.returncode,r.stdout+r.stderr)
 if name=='baseline':assert r.returncode!=0 and ('signature' in r.stderr or 'signedMessage' in r.stderr), 'Baseline failed for unexpected reason'
 else:assert r.returncode==0,'Patched regression failed'
