import test from 'node:test';
import assert from 'node:assert/strict';
import {createModelLoader} from './model-loader.mjs';
test('single-instance policy refuses another loaded LLM before any model load',async()=>{
  let loads=0;const config={modelBaseUrl:'http://127.0.0.1:1234',loadProfiles:{four:{parallel:1}},singleModelInstance:true};
  const loader=createModelLoader(config,async()=>new Response(JSON.stringify({models:[{key:'four',type:'llm',loaded_instances:[]},{key:'nine',type:'llm',loaded_instances:[{id:'nine'}]}]})),{execute:async()=>{loads++;return {exitCode:0};}});
  await assert.rejects(loader('four'),/Another model instance/);assert.equal(loads,0);
});
test('single-instance policy rejects a different loaded alias of the same resource',async()=>{
  const loader=createModelLoader({modelBaseUrl:'http://127.0.0.1:1234',loadProfiles:{four:{parallel:1}},singleModelInstance:true},
    async()=>new Response(JSON.stringify({models:[{key:'four',type:'llm',loaded_instances:[{id:'four-other',config:{parallel:1}}]}]})));
  await assert.rejects(loader('four'),/Another model instance/);
});
test('configured Windows model load is shared by concurrent callers and verifies native parallel1',async()=>{
  let loaded=false,calls=0;
  const config={modelBaseUrl:'http://127.0.0.1:1235',loadProfiles:{nine:{parallel:1}},modelLoadCommands:{nine:{program:'lms.exe',args:['load','nine','--parallel','1','--gpu','0.65']}}};
  const loader=createModelLoader(config,async()=>new Response(JSON.stringify({models:[{key:'nine',loaded_instances:loaded?[{config:{parallel:1}}]:[]}]})),{execute:async c=>{assert.ok(c.args.includes('0.65'));calls++;loaded=true;return {exitCode:0};}});
  await Promise.all([loader('nine'),loader('nine')]);assert.equal(calls,1);
});
test('wrong native parallel and loader failure cannot silently start an inference',async()=>{
  const config={modelBaseUrl:'http://127.0.0.1:1235',loadProfiles:{nine:{parallel:1}},modelLoadCommands:{nine:{program:'lms',args:[]}}};
  const wrong=createModelLoader(config,async()=>new Response(JSON.stringify({models:[{key:'nine',loaded_instances:[{config:{parallel:4}}]}]})));
  await assert.rejects(wrong('nine'),/parallel/);
  const failed=createModelLoader(config,async()=>new Response(JSON.stringify({models:[{key:'nine',loaded_instances:[]}]})),{execute:async()=>({exitCode:1,stderr:'VRAM error'})});
  await assert.rejects(failed('nine'),/VRAM error/);
});
