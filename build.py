#!/usr/bin/env python3
"""Portable SDK/NDK build for the RTL8153 USB Ethernet Android app."""
import argparse, hashlib, json, os, platform, shutil, subprocess, sys, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
p.add_argument('--build-tools', default='36.0.0', help='SDK build-tools version')
p.add_argument('--platform', default='android-35', help='SDK platform directory')
p.add_argument('--ndk', type=Path, help='Android NDK directory (full native rebuild)')
p.add_argument('--native-apk', type=Path, help='Reuse verified native libraries from an official 0.5.6/0.6.x APK instead of rebuilding them')
p.add_argument('--java-home', type=Path)
p.add_argument('--keystore', type=Path, help='Existing private signing keystore, stored outside this repository')
p.add_argument('--alias', default='rtl8153diagnostic')
p.add_argument('--password-env', default='KEYSTORE_PASSWORD', help='Environment variable holding the signing password')
p.add_argument('--unsigned', action='store_true')
p.add_argument('--test-only', action='store_true', help='Run portable Java and resource checks; no Android SDK/NDK required')
a = p.parse_args()
build = ROOT/'.build'; out = ROOT/'dist'
for d in [build, out, build/'classes', build/'dex', build/'test-classes']: d.mkdir(parents=True, exist_ok=True)
exe = '.exe' if os.name == 'nt' else ''
def java_tool(name):
    result = a.java_home/'bin'/(name+exe) if a.java_home else shutil.which(name)
    if not result: raise SystemExit('JDK 17+ is required: missing '+name)
    return str(result)
java, javac, keytool = [java_tool(s) for s in ['java','javac','keytool']]
commands = []
def run(args):
    args = [str(v) for v in args]
    proc = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding='utf-8', errors='replace')
    commands.append(subprocess.list2cmdline(args)+'\n'+proc.stdout)
    (build/'build.log').write_text('\n'.join(commands), encoding='utf-8')
    if proc.returncode: raise RuntimeError(proc.stdout)
    return proc.stdout
pkg = ROOT/'src/cz/codex/rtl8153diagnostic'
unit_sources = [pkg/(n+'.java') for n in ['L','DescriptorUtil','EthernetFrames','IpConfig','LanPackets']]
unit_tests = sorted((ROOT/'tests').glob('*Test.java'))
run([javac,'-encoding','UTF-8','-d',build/'test-classes',*unit_sources,*unit_tests])
tests = ''.join(run([java,'-cp',build/'test-classes',t.stem]) for t in unit_tests)
print(tests, end='')
resource_test = run([sys.executable, ROOT/'tests/check_resources.py']); print(resource_test, end='')
if a.test_only: sys.exit(0)
if not a.sdk: raise SystemExit('Provide --sdk or set ANDROID_HOME.')
if bool(a.ndk) == bool(a.native_apk): raise SystemExit('Choose exactly one: --ndk or --native-apk.')
bt = a.sdk/'build-tools'/a.build_tools; jar = a.sdk/'platforms'/a.platform/'android.jar'
if not jar.is_file(): raise SystemExit('Missing Android platform: '+str(jar))
run([javac,'-encoding','UTF-8','-source','8','-target','8','-bootclasspath',str(jar)+os.pathsep+str(bt/'core-lambda-stubs.jar'),'-d',build/'classes',*sorted((ROOT/'src').rglob('*.java'))])
run([java,'-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--lib',jar,'--min-api','26','--output',build/'dex',*sorted((build/'classes').rglob('*.class'))])
native = {}
# Hashes of the native libraries used by the tested 0.6.1 release.
known = {'armeabi-v7a':'e58e97a2d4a7704aa2a23411622d10a746799bed2ce306759fc9270e369a31d3','arm64-v8a':'e9889828cc18a1c10c8e807793a1c18531875a297ec0fea33b1b9fc194a1c60f'}
if a.native_apk:
    with zipfile.ZipFile(a.native_apk) as z:
        for abi, digest in known.items():
            name='lib/'+abi+'/libecmfast.so'; data=z.read(name)
            if hashlib.sha256(data).hexdigest()!=digest: raise SystemExit('Unrecognized native library: '+name)
            native[name]=data
else:
    host={'Darwin':'darwin-x86_64','Linux':'linux-x86_64','Windows':'windows-x86_64'}.get(platform.system())
    if not host: raise SystemExit('Unsupported NDK host')
    clang=a.ndk/'toolchains/llvm/prebuilt'/host/'bin'/('clang++'+exe)
    for abi,target in [('armeabi-v7a','armv7a-linux-androideabi26'),('arm64-v8a','aarch64-linux-android26')]:
        lib=build/('libecmfast-'+abi+'.so')
        run([clang,'--target='+target,'-std=c++17','-Wall','-Wextra','-O3','-DNDEBUG','-fPIC','-fvisibility=hidden','-fno-exceptions','-fno-rtti','-shared','-static-libstdc++','-Wl,-z,max-page-size=16384',ROOT/'native/ecmfast.cpp','-o',lib])
        native['lib/'+abi+'/libecmfast.so']=lib.read_bytes()
run([bt/('aapt2'+exe),'compile','--dir',ROOT/'res','-o',build/'resources.zip'])
run([bt/('aapt2'+exe),'link','-I',jar,'--manifest',ROOT/'AndroidManifest.xml','-o',build/'unsigned.apk',build/'resources.zip'])
with zipfile.ZipFile(build/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
    for f in sorted((ROOT/'licenses').glob('*')): z.write(f,'assets/licenses/'+f.name)
    for n,data in native.items(): z.writestr(n,data)
    for f in sorted((build/'dex').glob('*.dex')): z.write(f,f.name)
version=ET.parse(ROOT/'AndroidManifest.xml').getroot().attrib['{http://schemas.android.com/apk/res/android}versionName']
unsigned=out/('RTL8153-USB-Ethernet-'+version+'-unsigned.apk')
run([bt/('zipalign'+exe),'-f','4',build/'unsigned.apk',unsigned])
verification={'version':version,'tests':tests,'resources':resource_test,'native_sha256':{n:hashlib.sha256(b).hexdigest() for n,b in native.items()},'signed':False}
if not a.unsigned:
    key=a.keystore or build/'development-signing-key.p12'
    env_name=a.password_env
    if a.keystore:
        if not os.environ.get(env_name): raise SystemExit('Set '+env_name+' before signing, or use --unsigned.')
    else:
        env_name='RTL8153_DEVELOPMENT_PASSWORD'; os.environ[env_name]='android'
        if not key.exists(): run([keytool,'-genkeypair','-keystore',key,'-storetype','PKCS12','-storepass:env',env_name,'-keypass:env',env_name,'-alias',a.alias,'-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=RTL8153 Local Development'])
    apk=out/('RTL8153-USB-Ethernet-'+version+'.apk')
    run([java,'-jar',bt/'lib/apksigner.jar','sign','--ks',key,'--ks-key-alias',a.alias,'--ks-pass','env:'+env_name,'--key-pass','env:'+env_name,'--v4-signing-enabled','false','--out',apk,unsigned])
    signature=run([java,'-jar',bt/'lib/apksigner.jar','verify','--verbose','--print-certs',apk])
    run([bt/('zipalign'+exe),'-c','4',apk])
    verification.update(signed=True,sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),signature=signature)
    print('Signed APK:',apk)
else: print('Unsigned APK:',unsigned)
(out/'build-verification.json').write_text(json.dumps(verification,indent=2),encoding='utf-8')
