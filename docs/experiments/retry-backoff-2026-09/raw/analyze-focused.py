import json,collections,statistics,pathlib,re
root=pathlib.Path('/home/michele/Documenti/nanofaas/docs/experiments/retry-backoff-2026-09/raw')
out={}
for variant in ['baseline','candidate']:
 events=json.load(open('/tmp/retry-focused-'+variant+'.json'))['recording']['events']
 tops=collections.Counter();threads=collections.Counter();parks=[];loads=[]
 for x in events:
  v=x['values']
  if x['type']=='jdk.ExecutionSample':
   frames=(v.get('stackTrace') or {}).get('frames',[])
   threads[v['sampledThread']['javaName']]+=1
   if frames:tops[frames[0]['method']['type']['name']+'.'+frames[0]['method']['name']]+=1
  if x['type']=='jdk.ThreadPark' and v['eventThread']['javaName']=='nanofaas-scheduler-engine':
   def secs(s):return float(s[2:-1]) if s.startswith('PT') and s.endswith('S') else 0
   duration,timeout=secs(v['duration']),secs(v['timeout'])
   if timeout>0:parks.append(max(0,duration-timeout)*1e6)
  if x['type']=='jdk.CPULoad':loads.append(v['machineTotal'])
 samples=[json.loads(x) for x in (root/(variant+'-focused.log')).read_text().splitlines() if x.startswith('{"kind":"sample"')]
 out[variant]={'executionSamples':sum(threads.values()),'samplesByThread':dict(threads),'topFrames':tops.most_common(12),'schedulerParkCount':len(parks),'schedulerParkOvershootUs':({'median':statistics.median(parks),'p99':sorted(parks)[int(.99*(len(parks)-1))],'max':max(parks)} if parks else None),'machineCpuLoad':{'median':statistics.median(loads),'max':max(loads)},'arms':[{'arm':x['arm'],'cpuUs':x['threadCpuPerUsefulCompletionNanos']/1000,'p99Ms':x['p99Nanos']/1e6} for x in samples]}
(root/'focused-findings.json').write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps(out,indent=2))
