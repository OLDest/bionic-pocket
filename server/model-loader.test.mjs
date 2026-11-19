import test from 'node:test';
import assert from 'node:assert/strict';
import {createModelLoader} from './model-loader.mjs';
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
