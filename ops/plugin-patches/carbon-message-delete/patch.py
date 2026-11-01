#!/usr/bin/env python3
import hashlib,pathlib,subprocess,sys,zipfile
source,output,libraries,scratch=map(pathlib.Path,sys.argv[1:])
assert hashlib.sha256(source.read_bytes()).hexdigest()=='a5c6b5cbcd08f5bfa4c29c93cce4e74319fd5acf73a53024c8b3a51453be4c7a', 'Unexpected original JAR'
assert not output.exists(), 'Refusing overwrite'
scratch.mkdir(parents=True,exist_ok=True)
cp=':'.join(str(libraries/f'org/ow2/asm/{a}/9.9.1/{a}-9.9.1.jar') for a in ['asm','asm-tree'])
subprocess.run(['javac','-cp',cp,'-d',str(scratch),str(pathlib.Path(__file__).with_name('Patch.java'))],check=True)
member='net/draycia/carbonMessageDelete/CarbonMessageDelete.class'
with zipfile.ZipFile(source) as z:
 (scratch/'original.class').write_bytes(z.read(member))
 subprocess.run(['java','-cp',str(scratch)+':'+cp,'Patch',str(scratch/'original.class'),str(scratch/'patched.class')],check=True)
 with zipfile.ZipFile(output,'x') as out:
  for info in z.infolist():out.writestr(info,(scratch/'patched.class').read_bytes() if info.filename==member else z.read(info))
with zipfile.ZipFile(source) as a,zipfile.ZipFile(output) as b:
 assert a.namelist()==b.namelist()
 assert all(a.read(n)==b.read(n) for n in a.namelist() if n!=member)
print(hashlib.sha256(output.read_bytes()).hexdigest(),output)
