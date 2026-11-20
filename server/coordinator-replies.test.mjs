import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {createPhoneControl} from './phone-control.mjs';
test('coordinator receipt/result persist, preserve concurrent delivery ack, isolate chat and reject forged route',async t=>{
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'codex-replies-'));t.after(()=>fs.rm(root,{recursive:true,force:true}));
 const config={mobileControlEnabled:true,workspaces:{worker:{path:root}},routes:{parent:{hostId:'local',workspaceIds:['worker']}}};
 let c=await createPhoneControl(config,root);const input={requestId:randomUUID(),chatId:randomUUID(),workspaceId:'worker',text:'Передай в Codex: исправь трек',history:[]};
 await c.execute('handoff_to_codex',{},input);const p=(await c.pending())[0];const route={parentThreadId:'parent',hostId:'local',sha256:p.sha256};
 await assert.rejects(c.reply(p.id,{...route,sha256:'wrong',status:'received',message:'Received'}),/route\/hash/);
 await Promise.all([c.reply(p.id,{...route,status:'received',message:'Actual parent receipt'}),c.acknowledge(p.id,{...route,receipt:'CLI acceptance'})]);
 c=await createPhoneControl(config,root);let packet=await c.getHandoff(p.id);assert.equal(packet.state,'delivered');assert.equal(packet.reply.status,'received');
 assert.deepEqual(await c.replies(randomUUID()),[]);let replies=await c.replies(input.chatId);assert.equal(replies.length,1);assert.equal(replies[0].message,'Actual parent receipt');
 const r=await c.reply(p.id,{...route,status:'completed',message:'Real checks passed; APK not published'});assert.equal((await c.reply(p.id,{...route,status:'completed',message:r.message})).version,r.version);
 await assert.rejects(c.reply(p.id,{...route,status:'in_progress',message:'Old status'}),/regress/);
});
