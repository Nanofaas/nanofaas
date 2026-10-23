import http from "k6/http";
import {Counter,Trend} from "k6/metrics";
const generated=new Counter("generated"), completed=new Counter("completed"), duration=new Trend("node_latency",true);
const nodes=JSON.parse(__ENV.NODES),seconds=Number(__ENV.SECONDS);
export const options={scenarios:Object.fromEntries(nodes.map(n=>[n.id,{executor:"constant-arrival-rate",rate:n.rate,timeUnit:"1s",duration:seconds+"s",preAllocatedVUs:16,maxVUs:32,exec:"invoke",env:{NODE:JSON.stringify(n)}}])),
summaryTrendStats:["avg","med","p(95)"],thresholds:Object.fromEntries(nodes.map(n=>["node_latency{node:"+n.id+"}",[]]))};
export function invoke(){const n=JSON.parse(__ENV.NODE);generated.add(1);
const response=http.post(n.url+"/v1/functions/logging-work:invoke",JSON.stringify({input:{iterations:20000000,working_set_bytes:4096,seed:7}}),{headers:{"Content-Type":"application/json","X-Trace-Id":"logging-overhead"},timeout:"5s",tags:{node:n.id}});
duration.add(response.timings.duration,{node:n.id});if(response.status===200 && response.json("status")==="success" && /"output"\s*:\s*(\d+)\s*[,}]/.exec(response.body)?.[1]===__ENV.EXPECTED_OUTPUT)completed.add(1);}
export function handleSummary(data){return {[__ENV.SUMMARY]:JSON.stringify(data,null,2)};}
