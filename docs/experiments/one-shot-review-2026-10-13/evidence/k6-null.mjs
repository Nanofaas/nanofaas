import fs from 'node:fs';
import vm from 'node:vm';
const events = [];
const row = {originalId:'request',phase:'campaign',epoch:0,origin:'edge',function:'f',scheduledAt:0,url:'http://fake',input:{}};
const context = vm.createContext({__ENV:{ONE_SHOT_VUS:'1',ONE_SHOT_DURATION:'10s'},
  open:()=>JSON.stringify([row]),Date:{now:()=>100000},console:{log:s=>events.push(JSON.parse(s))}});
const adapters = {
  'k6/http':{default:{post:()=>({status:200,body:'null',json:()=>null,headers:{},timings:{duration:1}})}},
  'k6':{sleep:()=>{}},'k6/execution':{default:{scenario:{iterationInTest:0}}},
  'k6/data':{SharedArray:class {constructor(_name,read){return read();}}}
};
const module = new vm.SourceTextModule(fs.readFileSync('/tmp/nanolab-one-shot-host-placement/packages/nanolab/src/nanolab/assets/k6/one-shot.js','utf8'),{context});
await module.link(name=>new vm.SyntheticModule(Object.keys(adapters[name]),function(){
  for(const [key,value] of Object.entries(adapters[name]))this.setExport(key,value);
},{context}));
await module.evaluate();
let error = null;
try {module.namespace.default();} catch (failure) {error=String(failure);}
const result = {events:events.map(e=>e.event),error};
fs.writeFileSync('/tmp/one-shot-plan-review-20261013/k6-null.json',JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify(result));
if (!error || events.some(e=>e.event==='result')) throw new Error('Bug not reproduced');
