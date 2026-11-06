import concurrent.futures,json,subprocess,time,urllib.request,urllib.error
cp='127.0.0.1:5000/nanofaas/control-plane-e02-p1-native:p1-direct-dispatch-20261105'
fn='127.0.0.1:5000/nanofaas/word-stats-java:recipe-d741d77edff0'
network='p1-native-validation'
def docker(*args): return subprocess.check_output(['docker',*args],text=True).strip()
def req(path,body=None,method=None):
 data=None if body is None else json.dumps(body).encode()
 r=urllib.request.Request('http://127.0.0.1:18085'+path,data=data,method=method,headers={'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(r,timeout=45) as response: return json.load(response)
 except urllib.error.HTTPError as e:
  print('HTTP ERROR',path,e.code,e.read().decode(),flush=True);raise
try:
 docker('network','create',network)
 docker('run','-d','--name','p1-native-registry','--network',network,'-p','127.0.0.1:5000:5000','registry:2')
 time.sleep(2)
 docker('push',fn)
 docker('run','-d','--name','p1-native-check','--user','0:0','--network',network,'-p','127.0.0.1:18085:8080','-v','/var/run/docker.sock:/var/run/docker.sock','-e','NANOFAAS_DEPLOYMENT_DEFAULT_BACKEND=container-local','-e','NANOFAAS_CONTAINER_LOCAL_RUNTIME_ADAPTER=docker-java','-e','NANOFAAS_CONTAINER_LOCAL_NETWORK_NAME='+network,'-e','NANOFAAS_CONTAINER_LOCAL_CALLBACK_URL=http://p1-native-check:8080/v1/internal/executions',cp)
 for i in range(90):
  try: metadata=req('/modules/build-metadata');break
  except (OSError,ValueError): time.sleep(1)
 else: raise AssertionError('native startup timeout')
 assert metadata['build']['type']=='native',metadata
 print('metadata',json.dumps(metadata),flush=True)
 for name in ['p1-native-a','p1-native-b']:
  registered=req('/v1/functions',{'name':name,'image':fn,'executionMode':'DEPLOYMENT','concurrency':4,'queueSize':200,'maxRetries':3,'timeoutMs':10000})
  assert registered.get('endpointUrl') is None,registered
  for replicas in [1,2]:
   req('/v1/functions/'+name+'/replicas',{'replicas':replicas},'PUT')
   for _ in range(90):
    status=req('/v1/functions/'+name+'/replicas')
    if status['readyReplicas']==replicas:break
    time.sleep(1)
   else:raise AssertionError(status)
   def invoke(_):return req('/v1/functions/'+name+':invoke',{'input':{'text':'hello world hello'}})
   with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
    results=list(executor.map(invoke,range(12)))
   print(name,replicas,json.dumps(results[0]),'completed',len(results),flush=True)
   assert all(r['status']=='success' and r['output']==results[0]['output'] and r['output']['wordCount']==3 for r in results),results
   assert len({r['executionId'] for r in results})==12,results
 print('images',docker('image','inspect',cp,fn,'--format','{{.Id}}'),flush=True)
 print('PASS native two functions, one/two replicas, concurrent requests',flush=True)
finally:
 try: print('control-plane-log',docker('logs','p1-native-check')[-5000:],flush=True)
 except subprocess.CalledProcessError:pass
 try:
  members=json.loads(docker('network','inspect',network))[0]['Containers']
  if members: docker('rm','-f',*members)
  docker('network','rm',network)
 except subprocess.CalledProcessError:pass
