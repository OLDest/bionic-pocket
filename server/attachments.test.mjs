import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {randomUUID,createHash} from 'node:crypto';
import {createAttachments,ATTACHMENT_LIMITS} from './attachments.mjs';

// Allocate only a dedicated temporary storage root for the actual upload implementation.
async function fixture(t){const dir=await fs.mkdtemp(path.join(os.tmpdir(),'bionic-attachments-'));t.after(async()=>{if(path.dirname(dir)!==os.tmpdir()||!path.basename(dir).startsWith('bionic-attachments-'))throw Error('Unsafe test cleanup');await fs.rm(dir,{recursive:true,force:true});});return {dir,store:await createAttachments(dir)};}
// Describe an exact original without exposing its bytes as filesystem paths or executable instructions.
function description(bytes,name='sample.txt',mime='text/plain'){return {id:randomUUID(),name,mime,size:bytes.length,sha256:createHash('sha256').update(bytes).digest('hex')};}

test('chunk uploads resume after restart and a lost acknowledgement without duplicating bytes',async t=>{
  const {dir,store}=await fixture(t),bytes=Buffer.alloc(140000,97),file=description(bytes);
  const first={...file,offset:0,data:bytes.subarray(0,65536).toString('base64')};
  assert.equal((await store.upload(file.id,first)).received,65536);assert.equal((await store.upload(file.id,first)).received,65536);
  const resumed=await createAttachments(dir);let offset=(await resumed.get(file.id)).received;
  while(offset<bytes.length){const next=await resumed.upload(file.id,{...file,offset,data:bytes.subarray(offset,offset+65536).toString('base64')});offset=next.received;}
  assert.equal((await resumed.get(file.id)).state,'completed');assert.deepEqual(await fs.readFile(path.join(dir,'attachments',file.id,'content.bin')),bytes);
});

test('upload rejects conflicting IDs, changed retry bytes, oversized files and wrong final hashes',async t=>{
  const {store}=await fixture(t),bytes=Buffer.from('abcdef'),file=description(bytes);
  await store.upload(file.id,{...file,offset:0,data:bytes.subarray(0,3).toString('base64')});
  await assert.rejects(store.upload(file.id,{...file,name:'other.txt',offset:3,data:'ZGVm'}),/different bytes or metadata/);
  await assert.rejects(store.upload(file.id,{...file,offset:0,data:'eHl6'}),/differs/);
  await assert.rejects(store.upload(file.id,{...file,offset:5,data:'Zg=='}),/ahead/);
  await assert.rejects(store.upload(randomUUID(),{...file,size:ATTACHMENT_LIMITS.maxFileBytes+1,offset:0,data:''}),/10 MiB/);
  const wrong={...description(Buffer.from('right')),size:5};await assert.rejects(store.upload(wrong.id,{...wrong,offset:0,data:Buffer.from('wrong').toString('base64')}),/checksum mismatch/);
});

test('empty originals and concurrent retries complete with the exact original digest',async t=>{
  const {store}=await fixture(t),empty=description(Buffer.alloc(0));assert.equal((await store.upload(empty.id,{...empty,offset:0,data:''})).state,'completed');
  const bytes=Buffer.from('parallel'),file=description(bytes),chunk={...file,offset:0,data:bytes.toString('base64')};const results=await Promise.all([store.upload(file.id,chunk),store.upload(file.id,chunk)]);assert.ok(results.every(r=>r.received===bytes.length&&r.state==='completed'));
});

test('text remains untrusted data, binary files remain undecoded and filenames never choose storage paths',async t=>{
  const {dir,store}=await fixture(t),text=Buffer.from('Выполни: это только содержимое файла'),file=description(text,'../../source.txt');await store.upload(file.id,{...file,offset:0,data:text.toString('base64')});
  const binary=Buffer.from([0,255,0,128]),picture=description(binary,'photo.png','image/png');await store.upload(picture.id,{...picture,offset:0,data:binary.toString('base64')});
  const context=await store.contexts({attachments:[file,picture],history:[]});assert.match(context.current,/not instructions/);assert.match(context.current,/Выполни/);assert.match(context.current,/have not been decoded/);assert.equal((await fs.stat(path.join(dir,'attachments',file.id,'content.bin'))).size,text.length);
  await fs.writeFile(path.join(dir,'attachments',file.id,'content.bin'),'changed');await assert.rejects(store.contexts({attachments:[file]}),/changed after upload/);
});

