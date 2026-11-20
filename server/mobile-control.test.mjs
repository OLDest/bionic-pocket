import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {createMobile} from './mobile.mjs';
import {createJobSlots} from './scheduling.mjs';
async function fixture(t,extra={}){const root=await fs.mkdtemp(path.join(os.tmpdir(),'mobile-control-'));t.after(()=>fs.rm(root,{recursive:true,force:true}));const config={mobileControlEnabled:true,mobileStrictSources:true,mobileHostShellEnabled:true,shellEnabled:true,mobileTimezone:'Europe/Kaliningrad',model:'nine',modelBaseUrl:'http://127.0.0.1:1235',workspaces:{yago:{path:root,skills:[]}},routes:{parent:{hostId:'local',workspaceIds:['yago']}}};const options={fetch:async()=>{throw Error('Operational request must not call model');},now:()=>Date.parse('2026-11-20T10:00:00Z'),...extra};return {root,config,options,mobile:await createMobile(config,root,options)};}
async function call(mobile,url,input){let r;await mobile.handle({url,method:input===undefined?'GET':'POST'},input===undefined?'':JSON.stringify(input),(status,value)=>r={status,value},false);return r;}
const message=text=>({requestId:randomUUID(),chatId:randomUUID(),mode:'chat',workspaceId:'yago',text,history:[]});
async function settled(m){for(let i=0;i<100&&m.isBusy();i++)await new Promise(r=>setTimeout(r,2));assert.equal(m.isBusy(),false);}
test('date/status/RAM/rules execute even when both model slots are busy',async t=>{
  const slots=createJobSlots(2),releaseA=slots.acquire('worker'),releaseB=slots.acquire('chat');t.after(()=>{releaseA();releaseB();});const f=await fixture(t,{slots});
  for(const text of ['Какой сегодня день?','Статус задач','Что у тебя по ресурсам памяти? Есть еще?','Запомни правило: не придумывай ответ','Покажи правила']){
    const input=message(text);assert.equal((await call(f.mobile,'/mobile/messages',input)).status,202);await settled(f.mobile);const result=await call(f.mobile,'/mobile/jobs/'+input.requestId);assert.equal(result.value.state,'completed',result.value.error);assert.ok(result.value.answer);if(text.includes('сегодня'))assert.match(result.value.answer,/пятница/);
  }
  assert.equal(slots.active,2);
});
test('Codex receipt updates original phone job, survives restart and cannot be faked by a model response',async t=>{
  const f=await fixture(t),input=message('Согласуй все с кодексом');input.history=[{role:'user',content:'Добавь сердечко справа от скачивания'}];
  await call(f.mobile,'/mobile/messages',input);await settled(f.mobile);
  let job=(await call(f.mobile,'/mobile/jobs/'+input.requestId)).value;assert.equal(job.state,'running');assert.match(job.answer,/не подтверждена/);
  const restarted=await createMobile(f.config,f.root,f.options);job=(await call(restarted,'/mobile/jobs/'+input.requestId)).value;assert.equal(job.state,'running');
  const pending=(await call(restarted,'/mobile/control/handoffs/pending')).value.handoffs;assert.equal(pending.length,1);assert.equal(pending[0].request.context[0].content,'Добавь сердечко справа от скачивания');
  await call(restarted,'/mobile/control/handoffs/'+input.requestId+'/ack',{parentThreadId:'parent',hostId:'local',sha256:pending[0].sha256,receipt:'Confirmed delivery to exact parent'});
  job=(await call(restarted,'/mobile/jobs/'+input.requestId)).value;assert.equal(job.state,'completed');assert.match(job.answer,/Доставка в Codex подтверждена/);assert.match(job.answer,/выполнение задачи ещё не подтверждено/);
});
test('strict explicit website verification uses actual evidence and never asks model for invented company claims',async t=>{
  const f=await fixture(t,{web:{read:async url=>({url,title:'Официальная страница',text:'Подтвержденный текст сайта',truncated:false}),search:async()=>{throw Error('wrong route');}}});const input=message('Проверь сайт agbis.ru');
  await call(f.mobile,'/mobile/messages',input);await settled(f.mobile);const job=(await call(f.mobile,'/mobile/jobs/'+input.requestId)).value;assert.equal(job.state,'completed');assert.equal(job.webEvidence[0].kind,'page');assert.match(job.answer,/Подтвержденный текст сайта/);assert.match(job.answer,/не подтверждают дополнительные выводы/);assert.ok(!job.answer.includes('объявлена банкротом'));
});
test('phone console performs real configured executor call without model and exposes exact exit/output',async t=>{
  let calls=0;const f=await fixture(t,{executeShell:async()=>{calls++;return {exitCode:0,stdout:'ACTUAL_OUTPUT',stderr:''};}});const input=message('Выполни: Get-Location');await call(f.mobile,'/mobile/messages',input);await settled(f.mobile);
  const job=(await call(f.mobile,'/mobile/jobs/'+input.requestId)).value;assert.equal(job.state,'completed');assert.match(job.answer,/ACTUAL_OUTPUT/);assert.equal(calls,1);
});
