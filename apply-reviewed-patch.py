"""Apply a coordinator-reviewed exact patch only to the selected worker checkout."""
import hashlib,json,os,pathlib,sys,tempfile
proposal=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
root=pathlib.Path(sys.argv[2]).resolve()
for item in proposal['files']:
    target=(root/item['path']).resolve()
    if not target.is_relative_to(root):raise RuntimeError('Patch target escapes worker')
    before=target.read_bytes()
    if hashlib.sha256(before).hexdigest()!=item['beforeSha256']:raise RuntimeError('Reviewed source hash changed; preserve worker and recapture patch')
    text=before.decode('utf-8-sig').replace('\r\n','\n')
    for edit in item['edits']:
        if not edit['oldText'] or text.count(edit['oldText'])!=1:raise RuntimeError('Reviewed anchor must match exactly once')
        text=text.replace(edit['oldText'],edit['newText'],1)
    if b'\r\n' in before:text=text.replace('\n','\r\n')
    if before.startswith(b'\xef\xbb\xbf'):text='\ufeff'+text
    after=text.encode('utf-8')
    with tempfile.NamedTemporaryFile(dir=target.parent,prefix='reviewed-',suffix='.tmp',delete=False) as out:out.write(after);name=out.name
    try:os.replace(name,target)
    finally:
        if os.path.exists(name):os.unlink(name)
    print(json.dumps({'applied':item['path'],'beforeSha256':item['beforeSha256'],'afterSha256':hashlib.sha256(after).hexdigest()}))
