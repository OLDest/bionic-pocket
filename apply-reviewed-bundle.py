"""Apply a hash-locked coordinator bundle; validate every input before writing."""
import hashlib,json,os,pathlib,sys,tempfile
bundle=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
root=pathlib.Path(sys.argv[2]).resolve()
pending=[]
for item in bundle['files']:
    target=(root/item['path']).resolve()
    if not target.is_relative_to(root) or '.git' in target.relative_to(root).parts:
        raise RuntimeError('Target escapes worker or modifies Git metadata')
    before=hashlib.sha256(target.read_bytes()).hexdigest() if target.is_file() else None
    if before!=item['beforeSha256']:raise RuntimeError('Source changed: '+item['path'])
    after=item['content'].encode('utf-8')
    if hashlib.sha256(after).hexdigest()!=item['afterSha256']:raise RuntimeError('Bundle hash mismatch')
    pending.append((target,after,item))
for target,after,item in pending:
    target.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=target.parent,prefix='reviewed-',suffix='.tmp',delete=False) as out:
        out.write(after);name=out.name
    try:os.replace(name,target)
    finally:
        if os.path.exists(name):os.unlink(name)
    print(json.dumps({'applied':item['path'],'beforeSha256':item['beforeSha256'],'afterSha256':item['afterSha256']}))
