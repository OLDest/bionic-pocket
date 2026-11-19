"""Build a signed Android APK using an installed Android SDK/JDK, no Gradle download."""
import hashlib,json,os,pathlib,secrets,subprocess,sys,zipfile
ROOT=pathlib.Path(__file__).resolve().parent
DEFAULT=ROOT.parents[1]/'2026-11-15'/'go-https-pikabu-ru-story-yandeks'/'yago'/'.tools'
SDK=pathlib.Path(os.environ.get('ANDROID_HOME',str(DEFAULT/'android-sdk')))
if os.environ.get('JAVA_HOME'):
    JDK=pathlib.Path(os.environ['JAVA_HOME'])
else:
    candidates=list((DEFAULT/'jdk').glob('jdk-*'))
    if not candidates: raise RuntimeError('Set JAVA_HOME to an installed JDK 17')
    JDK=candidates[0]
RTK=os.environ.get('RTK',r'C:\Users\OLDest\.local\bin\rtk.exe')
TOOLS=SDK/'build-tools'/'35.0.0'
PERSONAL='--personal' in sys.argv
WORK=ROOT/'build'/('personal' if PERSONAL else 'public'); DIST=ROOT/'dist'; LOCAL=ROOT/'.local'
def run(*args,env=None):
    subprocess.run([RTK,'proxy',*[str(a) for a in args]],check=True,cwd=ROOT,env=env)
def main():
    for p in [WORK,DIST,LOCAL,WORK/'generated',WORK/'classes',WORK/'dex']:p.mkdir(parents=True,exist_ok=True)
    version=json.loads((ROOT/'version.json').read_text())
    manifest=(ROOT/'android'/'AndroidManifest.xml').read_text()
    if f'android:versionCode="{version["code"]}"' not in manifest or f'android:versionName="{version["name"]}"' not in manifest: raise RuntimeError('Manifest/version.json mismatch')
    android=SDK/'platforms'/'android-35'/'android.jar'
    resources=WORK/'resources.zip'
    run(TOOLS/'aapt2.exe','compile','--dir',ROOT/'android'/'res','-o',resources)
    run(TOOLS/'aapt2.exe','link','-o',WORK/'resources.apk','--manifest',ROOT/'android'/'AndroidManifest.xml','-I',android,'--java',WORK/'generated','-R',resources,'--auto-add-overlay')
    sources=[*ROOT.glob('android/java/**/*.java'),*WORK.glob('generated/**/*.java')]
    bootclasspath=str(TOOLS/'core-lambda-stubs.jar')+os.pathsep+str(android)
    run(JDK/'bin'/'javac.exe','-encoding','UTF-8','-source','8','-target','8','-Xlint:-options','-bootclasspath',bootclasspath,'-d',WORK/'classes',*sources)
    run(JDK/'bin'/'jar.exe','cf',WORK/'app.jar','-C',WORK/'classes','.')
    run(JDK/'bin'/'java.exe','-cp',TOOLS/'lib'/'d8.jar','com.android.tools.r8.D8','--min-api','26','--lib',android,'--output',WORK/'dex',WORK/'app.jar')
    unsigned=WORK/'unsigned.apk'; unsigned.write_bytes((WORK/'resources.apk').read_bytes())
    with zipfile.ZipFile(unsigned,'a',compression=zipfile.ZIP_DEFLATED) as apk:
        for dex in (WORK/'dex').glob('*.dex'):apk.write(dex,dex.name)
        if PERSONAL:
            personal=json.loads((LOCAL/'personal.json').read_text(encoding='utf-8'))
            if len(personal.get('token',''))<32 or not personal.get('server','').startswith('https://'):raise RuntimeError('Personal connection settings missing')
            apk.writestr('assets/personal.json',json.dumps(personal))
    run(TOOLS/'zipalign.exe','-f','4',unsigned,WORK/'aligned.apk')
    key=LOCAL/'release.keystore'; password=LOCAL/'keystore-password.txt'
    if key.exists()!=password.exists():raise RuntimeError('Signing key/password pair incomplete; restore it rather than rotate')
    if not key.exists():
        password.write_text(secrets.token_urlsafe(32),encoding='utf-8')
        run(JDK/'bin'/'keytool.exe','-genkeypair','-noprompt','-keystore',key,'-storepass:file',password,'-keypass:file',password,'-alias','bionic-pocket','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=Bionic Pocket, O=OLDest')
    apk=(LOCAL/'BionicPocket-personal.apk') if PERSONAL else (DIST/'BionicPocket.apk')
    run(JDK/'bin'/'java.exe','-jar',TOOLS/'lib'/'apksigner.jar','sign','--ks',key,'--ks-key-alias','bionic-pocket','--ks-pass','file:'+str(password),'--out',apk,WORK/'aligned.apk')
    run(JDK/'bin'/'java.exe','-jar',TOOLS/'lib'/'apksigner.jar','verify','--verbose','--print-certs',apk)
    run(TOOLS/'zipalign.exe','-c','4',apk)
    sha=hashlib.sha256(apk.read_bytes()).hexdigest()
    if PERSONAL:
        (LOCAL/'personal-verification.json').write_text(json.dumps({'version':version,'sha256':sha,'size':apk.stat().st_size,'personal':True},indent=2))
        print('Personal APK ready (do not publish):',apk)
        return
    (DIST/'SHA256SUMS.txt').write_text(sha+'  BionicPocket.apk\n',encoding='utf-8')
    (DIST/'update.json').write_text(json.dumps({'versionCode':version['code'],'versionName':version['name'],'url':f'https://github.com/OLDest/bionic-pocket/releases/download/v{version["name"]}/BionicPocket.apk','sha256':sha,'size':apk.stat().st_size},indent=2)+'\n',encoding='utf-8')
    print('APK ready:',apk)
if __name__=='__main__':main()
