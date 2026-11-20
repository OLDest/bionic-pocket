"""Publish source/releases using the existing Git credential manager; never print tokens."""
import hashlib, json, os, pathlib, subprocess, sys, urllib.request, urllib.error, zipfile
ROOT = pathlib.Path(__file__).resolve().parent
RTK = os.environ.get('RTK', r'C:\Users\OLDest\.local\bin\rtk.exe')
GIT = os.environ.get('GIT', r'C:\Users\OLDest\AppData\Local\hermes\git\cmd\git.exe')
REPO = 'OLDest/bionic-pocket'
def credential():
    """Load only the expected cached account without opening an interactive account selector."""
    env=os.environ.copy();env.update(GCM_INTERACTIVE='Never',GIT_TERMINAL_PROMPT='0')
    p = subprocess.run([RTK, 'proxy', GIT, '-c', 'credential.username=OLDest', 'credential', 'fill'], input='protocol=https\nhost=github.com\nusername=OLDest\n\n', text=True, capture_output=True, check=True, env=env)
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
    """Publish a verified public APK from the exact pushed source commit and reviewed release notes."""
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
        notes_file=os.environ.get('BIONIC_RELEASE_NOTES')
        if not notes_file:raise RuntimeError('Set BIONIC_RELEASE_NOTES to the reviewed release description')
        target=subprocess.run([RTK,'proxy',GIT,'rev-parse','HEAD'],cwd=ROOT,capture_output=True,text=True,check=True).stdout.strip()
        if len(target)!=40 or any(c not in '0123456789abcdef' for c in target):raise RuntimeError('Cannot determine exact source commit')
        rel=api(token,'/repos/'+REPO+'/releases',{'tag_name':tag,'target_commitish':target,'name':'Bionic Pocket '+version['name'],'body':pathlib.Path(notes_file).read_text(encoding='utf-8'),'draft':True})
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
