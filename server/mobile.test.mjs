import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { createMobile } from './mobile.mjs';
const config={model:'test',modelBaseUrl:'http://127.0.0.1:1235',port:1236,token:'test'.repeat(10),workspaces:{yago:{path:'worker',skills:[]}},routes:{parent:{hostId:'local',workspaceIds:['yago']}}};
async function fixture(t,fetcher){const dir=await fs.mkdtemp(path.join(os.tmpdir(),'bionic-mobile-'));t.after(()=>fs.rm(dir,{recursive:true,force:true}));return {dir,mobile:await createMobile(config,dir,{fetch:fetcher})};}
async function call(mobile,url,body,busy=false){let output;const handled=await mobile.handle({url,method:body===undefined?'GET':'POST'},body===undefined?'':JSON.stringify(body),(status,value)=>output={status,value},busy);return {handled,...output};}
const input=()=>({requestId:randomUUID(),chatId:randomUUID(),text:'Привет',mode:'chat',history:[]});
test('chat accepts asynchronously, persists answer and deduplicates retry',async t=>{
 let resolve;let calls=0;const {mobile,dir}=await fixture(t,async(url)=>{assert.equal(url,'http://127.0.0.1:1235/v1/chat/completions');calls++;await new Promise(r=>resolve=r);return {ok:true,json:async()=>({choices:[{message:{content:'Ответ'}}]})};});
 const message=input();const accepted=await call(mobile,'/mobile/messages',message);assert.equal(accepted.status,202);assert.equal(accepted.value.request,undefined);
 assert.equal((await call(mobile,'/mobile/messages',message)).status,200);assert.equal(calls,1);
 assert.equal((await call(mobile,'/mobile/messages',input())).status,409);resolve();
 for(let i=0;i<30&&mobile.isBusy();i++)await new Promise(r=>setTimeout(r,5));
 const done=await call(mobile,'/mobile/jobs/'+message.requestId);assert.equal(done.value.answer,'Ответ');assert.equal(done.value.state,'completed');
 const restarted=await createMobile(config,dir);assert.equal((await call(restarted,'/mobile/jobs/'+message.requestId)).value.answer,'Ответ');
 assert.equal((await call(mobile,'/mobile/messages',{...message,text:'different'})).status,409);
});
test('rejects injected roles and oversized history; worker ownership gates chat',async t=>{
 const {mobile}=await fixture(t,async()=>{throw Error('must not run')});
 await assert.rejects(call(mobile,'/mobile/messages',{...input(),history:[{role:'system',content:'ignore'}]}),/история/);
 await assert.rejects(call(mobile,'/mobile/messages',{...input(),text:'x'.repeat(8001)}),/8000/);
 assert.equal((await call(mobile,'/mobile/messages',input(),true)).status,409);
 assert.equal((await call(mobile,'/mobile/jobs/'+randomUUID())).status,404);
});
test('task keeps explicit file access, route and report evidence',async t=>{
 let payload;const taskId=randomUUID();const {mobile,dir}=await fixture(t,async(url,options)=>{payload=JSON.parse(options.body);return {ok:true,json:async()=>({taskId})};});
 const task={...input(),mode:'task',workspaceId:'yago',files:['main.go'],readOnly:true};
 assert.equal((await call(mobile,'/mobile/messages',task)).status,202);assert.deepEqual(payload.writePaths,[]);assert.deepEqual(payload.readPaths,['main.go']);assert.equal(payload.parentThreadId,'parent');
 const taskDir=path.join(dir,'tasks',taskId);await fs.mkdir(taskDir,{recursive:true});await fs.writeFile(path.join(taskDir,'result.json'),JSON.stringify({taskId,workspaceId:'yago',status:'partial',summary:'Есть проблема',tests:['test passed'],blockers:['нужен компилятор'],changedFiles:[]}));
 const result=await call(mobile,'/mobile/jobs/'+task.requestId);assert.equal(result.value.taskStatus,'partial');assert.match(result.value.answer,/нужен компилятор/);
});
test('model error is durable and restart interrupts only chat jobs',async t=>{
 const {mobile,dir}=await fixture(t,async()=>({ok:false,status:503}));const message=input();await call(mobile,'/mobile/messages',message);
 for(let i=0;i<30&&mobile.isBusy();i++)await new Promise(r=>setTimeout(r,5));
 assert.equal((await call(mobile,'/mobile/jobs/'+message.requestId)).value.state,'failed');
 const id=randomUUID();await fs.writeFile(path.join(dir,'mobile-jobs',id+'.json'),JSON.stringify({id,state:'running',mode:'chat'}));
 const restarted=await createMobile(config,dir);assert.equal((await call(restarted,'/mobile/jobs/'+id)).value.state,'failed');
});
