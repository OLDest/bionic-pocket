import test from 'node:test';
import assert from 'node:assert/strict';
import {serializeModelFetch} from './scheduling.mjs';
test('model queue distinguishes waiting from running and strips internal telemetry callback',async()=>{
  let release;const held=new Promise(r=>release=r),events=[];
  const fetcher=serializeModelFetch(async(url,options)=>{assert.equal(options.onStage,undefined);await held;return new Response('{}');},'http://127.0.0.1:1235');
  const one=fetcher('http://127.0.0.1:1235/v1/chat/completions',{onStage:e=>events.push(e.stage)});
  await new Promise(r=>setImmediate(r));
  const two=fetcher('http://127.0.0.1:1235/v1/chat/completions');await new Promise(r=>setImmediate(r));
  assert.equal(fetcher.snapshot().active,1);assert.equal(fetcher.snapshot().waiting,1);assert.ok(events.includes('awaiting-model-response'));
  release();await Promise.all([one,two]);assert.equal(fetcher.snapshot().active,0);assert.equal(fetcher.snapshot().waiting,0);
});
test('timeout while writing telemetry cannot dispatch an already-aborted request',async()=>{
  const controller=new AbortController();let calls=0;
  const fetcher=serializeModelFetch(async()=>{calls++;return new Response('{}');},'http://127.0.0.1:1235');
  await assert.rejects(fetcher('http://127.0.0.1:1235/v1/chat/completions',{signal:controller.signal,onStage:e=>{if(e.stage==='awaiting-model-response')controller.abort();}}),/abort/i);
  assert.equal(calls,0);assert.equal(fetcher.snapshot().active,0);
});
