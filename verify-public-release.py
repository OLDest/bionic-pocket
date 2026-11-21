"""Verify real public assets, updater integrity and signing continuity."""
import hashlib,json,pathlib,subprocess,sys,urllib.request,zipfile,re
root=pathlib.Path(__file__).resolve().parent
version=json.loads((root/'version.json').read_text())
base='https://github.com/OLDest/bionic-pocket/releases/'
before='--before' in sys.argv
with urllib.request.urlopen(base+'latest/download/update.json',timeout=30) as r:manifest=json.load(r)
if before:
    assert manifest['versionCode']<version['code'],'Current release is already at or above proposed version'
else:
    assert manifest['versionCode']==version['code'] and manifest['versionName']==version['name']
with urllib.request.urlopen(manifest['url'],timeout=30) as r:published=r.read()
assert len(published)==manifest['size'] and hashlib.sha256(published).hexdigest()==manifest['sha256']
local=root/'dist/BionicPocket.apk'
with zipfile.ZipFile(local) as z:
    assert 'assets/personal.json' not in z.namelist()
    assert not any(n.endswith(('.keystore','.jks')) for n in z.namelist())
if before:
    old=root/'.local/previous-public.apk';old.parent.mkdir(exist_ok=True);old.write_bytes(published)
    tools=pathlib.Path('C:/Users/OLDest/Documents/Codex/2026-11-15/go-https-pikabu-ru-story-yandeks/yago/.tools')
    java=tools/'jdk/jdk-17.0.21+9/bin/java.exe'
    def certificate(apk):
        p=subprocess.run(['rtk','proxy',str(java),'-jar',str(tools/'android-sdk/build-tools/35.0.0/lib/apksigner.jar'),'verify','--print-certs',str(apk)],check=True,capture_output=True,text=True)
        return re.search(r'certificate SHA-256 digest: ([0-9a-f]+)',p.stdout).group(1)
    assert certificate(local)==certificate(old),'Signing identity changed'
    output={'previous':manifest['versionName'],'publicApk':True,'samePublishedSigner':True,'localSha256':hashlib.sha256(local.read_bytes()).hexdigest()}
else:
    assert published==local.read_bytes(),'Published APK differs from reviewed build'
    with urllib.request.urlopen(base+'latest/download/SHA256SUMS.txt',timeout=30) as r:sums=r.read().decode()
    assert manifest['sha256']+'  BionicPocket.apk' in sums
    output={'version':version['name'],'publishedAssetsVerified':True,'sha256':manifest['sha256'],'size':len(published)}
(root/'.local'/('prepublication.json' if before else 'published-verification.json')).write_text(json.dumps(output,indent=2))
print(json.dumps(output,indent=2))
