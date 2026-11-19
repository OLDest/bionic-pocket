import test from 'node:test';
import assert from 'node:assert/strict';
import { createJobSlots, serializeModelFetch, createSubmissionGate } from './scheduling.mjs';
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };

test('two slots release idempotently and never exceed capacity', () => {
  let available = 0;
  const slots = createJobSlots(2, () => available++);
  const first = slots.acquire('coordinator'), second = slots.acquire('phone');
  assert.equal(slots.active, 2); assert.equal(slots.acquire('third'), null);
  first(); first(); assert.equal(slots.active, 1); assert.equal(available, 1);
  second(); assert.equal(slots.active, 0);
  assert.throws(() => createJobSlots(3));
});

test('inference waits for the full response body while unrelated bridge calls pass through', async () => {
  const body = deferred(); const firstStarted = deferred(); const calls = [];
  const fetcher = serializeModelFetch(async url => {
    calls.push(url);
    if (url.endsWith('/first')) { firstStarted.resolve(); return { ok: true, status: 200, json: () => body.promise }; }
    return { ok: true, status: 200, json: async () => ({ url }) };
  }, 'http://127.0.0.1:1235');
  const first = fetcher('http://127.0.0.1:1235/first'); await firstStarted.promise;
  const second = fetcher('http://127.0.0.1:1235/second');
  await fetcher('http://127.0.0.1:1236/tasks');
  await fetcher('http://127.0.0.1:1235/api/v1/models');
  assert.equal(calls.length, 3); assert.ok(!calls.some(url => url.endsWith('/second')));
  body.resolve({ answer: 'first' }); await first; await second;
  assert.ok(calls.at(-1).endsWith('/second'));
});

test('cancelled queued inference returns promptly without opening the model lane early', async () => {
  const body = deferred(), started = deferred(); const calls = [];
  const fetcher = serializeModelFetch(async url => {
    calls.push(url);
    if (url.endsWith('/first')) { started.resolve(); return { ok: true, status: 200, json: () => body.promise }; }
    return { ok: true, status: 200, json: async () => ({}) };
  }, 'http://127.0.0.1:1235');
  const first = fetcher('http://127.0.0.1:1235/first'); await started.promise;
  const abort = new AbortController();
  const second = fetcher('http://127.0.0.1:1235/cancelled', { signal: abort.signal });
  abort.abort(Error('cancelled while waiting'));
  await assert.rejects(second, /cancelled while waiting/);
  const third = fetcher('http://127.0.0.1:1235/third');
  await new Promise(resolve => setImmediate(resolve)); assert.equal(calls.length, 1);
  body.resolve({}); await first; await third;
  assert.deepEqual(calls.map(url => url.split('/').at(-1)), ['first', 'third']);
});

test('model errors release the inference lane for the next request', async () => {
  let count = 0;
  const fetcher = serializeModelFetch(async () => {
    if (++count === 1) return { ok: false, status: 500, json: async () => { throw Error('bad response'); } };
    return { ok: true, status: 200, json: async () => ({ ok: true }) };
  }, 'http://127.0.0.1:1235');
  const first = fetcher('http://127.0.0.1:1235/first');
  const next = fetcher('http://127.0.0.1:1235/next');
  await assert.rejects(first, /bad response/); assert.deepEqual(await (await next).json(), { ok: true });
});

test('simultaneous admissions queue FIFO and obey the admission bound', async () => {
  const gate = createSubmissionGate(1);
  const first = await gate.acquire();
  const pending = gate.acquire();
  assert.equal(await gate.acquire(), null);
  assert.equal(gate.busy, true); first(); first();
  const second = await pending; assert.equal(typeof second, 'function');
  assert.equal(gate.busy, true); second(); assert.equal(gate.busy, false);
});
