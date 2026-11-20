"""Compile Android sources without signing, publishing or accessing credentials."""
import os,pathlib,subprocess
root=pathlib.Path(__file__).resolve().parent
tools=pathlib.Path('C:/Users/OLDest/Documents/Codex/2026-11-15/go-https-pikabu-ru-story-yandeks/yago/.tools')
sdk=tools/'android-sdk'; jdk=next((tools/'jdk').glob('jdk-*')); build=sdk/'build-tools/35.0.0'
out=root/'build/source-verification'
for name in ['generated','classes']: (out/name).mkdir(parents=True,exist_ok=True)
rtk='C:/Users/OLDest/.local/bin/rtk.exe'
def run(*args):subprocess.run([rtk,'proxy',*[str(x) for x in args]],cwd=root,check=True)
android=sdk/'platforms/android-35/android.jar'
run(build/'aapt2.exe','compile','--dir',root/'android/res','-o',out/'resources.zip')
run(build/'aapt2.exe','link','-o',out/'resources.apk','--manifest',root/'android/AndroidManifest.xml','-I',android,'--java',out/'generated','-R',out/'resources.zip','--auto-add-overlay')
run(jdk/'bin/javac.exe','-encoding','UTF-8','-source','8','-target','8','-Xlint:-options','-bootclasspath',str(build/'core-lambda-stubs.jar')+os.pathsep+str(android),'-d',out/'classes',*root.glob('android/java/**/*.java'),*out.glob('generated/**/*.java'))
print('Android source compile passed; no APK signed or published.')
