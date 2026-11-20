"""Publish source/releases using the existing Git credential manager; never print tokens."""
import hashlib, json, os, pathlib, subprocess, sys, urllib.request, urllib.error, zipfile
ROOT = pathlib.Path(__file__).resolve().parent
RTK = os.environ.get('RTK', r'C:\Users\OLDest\.local\bin\rtk.exe')
GIT = os.environ.get('GIT', r'C:\Users\OLDest\AppData\Local\hermes\git\cmd\git.exe')
REPO = 'OLDest/bionic-pocket'
def credential():
    p = subprocess.run([RTK, 'proxy', GIT, 'credential', 'fill'], input='protocol=https\nhost=github.com\n\n', text=True, capture_output=True, check=True)
    values = dict(line.split('=',1) for line in p.stdout.splitlines() if '=' in line)
    token = values.get('password')
    if not token: raise RuntimeError('GitHub credential is unavailable')
    return token
def api(token, route, data=None, method=None, content_type='application/json'):
    headers = {'Authorization':'Bearer '+token, 'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28','User-Agent':'BionicPocket-release'}
    if data is not None:
        headers['Content-Type']=content_type
        if content_type=='application/json': data=json.dumps(data).encode()
    req=urllib.request.Request(route if route.startswith('https://') else 'https://api.github.com'+route, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req,timeout=60) as r: return json.load(r)
def main():
    token=credential()
    user=api(token,'/user')
    if user['login'].lower()!='oldest': raise RuntimeError('Unexpected GitHub account')
    if len(sys.argv)<2 or sys.argv[1]=='whoami': print('GitHub account:',user['login']); return
    if sys.argv[1]=='create':
        try:
            api(token,'/repos/'+REPO)
            raise RuntimeError('Repository already exists; inspect before reuse')
        except urllib.error.HTTPError as e:
            if e.code!=404: raise
        repo=api(token,'/user/repos',{'name':'bionic-pocket','description':'Android client for a self-hosted Bionic programming assistant. Chats, scoped tasks and signed app updates.','private':False,'auto_init':False})
        print(repo['html_url']); return
    if sys.argv[1]=='release':
        with zipfile.ZipFile(ROOT/'dist'/'BionicPocket.apk') as apk:
            if 'assets/personal.json' in apk.namelist(): raise RuntimeError('Refusing to publish a personal APK')
        version=json.loads((ROOT/'version.json').read_text())
        tag='v'+version['name']
        rel=api(token,'/repos/'+REPO+'/releases',{'tag_name':tag,'name':'Bionic Pocket '+version['name'],'body':'Подтверждения получения и результаты Codex возвращаются в исходный телефонный разговор отдельной карточкой, даже если исходное сообщение уже получило статус доставки. Новая версия ответа обновляет ту же карточку без дублей. Проверка ответов выполняется только при открытом приложении, без вызова модели для пустых проверок. Проверены реальные Java-методы получения и обновления карточки, повторная доставка, API, Android-сборка и подпись APK. История и настройки подключения сохраняются.','draft':True})
        try:
            for name in ['BionicPocket.apk','SHA256SUMS.txt','update.json']:
                file=ROOT/'dist'/name
                api(token,rel['upload_url'].split('{')[0]+'?name='+name,file.read_bytes(),method='POST',content_type='application/octet-stream')
            rel=api(token,'/repos/'+REPO+'/releases/'+str(rel['id']),{'draft':False},method='PATCH')
            print(rel['html_url'])
        except Exception:
            print('Release remains a draft; inspect GitHub before retrying',file=sys.stderr)
            raise
if __name__=='__main__': main()
