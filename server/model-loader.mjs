import {execFile} from 'node:child_process';
function execute(command,rtk){return new Promise(resolve=>execFile(rtk??'rtk',['proxy',command.program,...command.args],{windowsHide:true,timeout:120000,maxBuffer:1048576,encoding:'utf8'},(error,stdout,stderr)=>resolve({exitCode:error?error.code??-1:0,stderr,stdout})));}
export function createModelLoader(config,fetcher=fetch,options={}){
  const pending=new Map();
  return async function ensureModel(model){
    if(!config.loadProfiles?.[model])return;
    if(pending.has(model))return pending.get(model);
    const work=(async()=>{
      const base=config.modelBaseUrl.replace(/\/$/,'');
      async function inventory(){const r=await fetcher(base+'/api/v1/models',{redirect:'error',signal:AbortSignal.timeout(10000)});if(!r.ok)throw Error('Local model inventory failed');const m=(await r.json()).models?.find(m=>m.key===model);if(!m)throw Error('Assigned local model is not installed');return m;}
      let current=await inventory();
      if(!current.loaded_instances?.length){
        const command=config.modelLoadCommands?.[model];
        if(command){const result=await (options.execute??execute)(command,config.rtkPath);if(result.exitCode!==0)throw Error('Configured local model load failed: '+String(result.stderr??'').slice(0,1000));}
        else{const loaded=await fetcher(base+'/api/v1/models/load',{method:'POST',headers:{'content-type':'application/json'},redirect:'error',signal:AbortSignal.timeout(180000),body:JSON.stringify({model,...config.loadProfiles[model]})});if(!loaded.ok)throw Error('Local model load failed');}
        current=await inventory();
      }
      if(!current.loaded_instances?.length)throw Error('Model loader returned without a loaded instance');
      const expected=config.loadProfiles[model].parallel;
      if(expected&&current.loaded_instances.some(i=>i.config?.parallel!==expected))throw Error('Loaded model parallel setting differs from configured profile; reload while idle');
    })();
    pending.set(model,work);
    try{return await work;}finally{pending.delete(model);}
  };
}
