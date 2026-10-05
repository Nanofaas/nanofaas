package it.unimib.datai.nanofaas.modules.offload.oneshot;
import org.junit.jupiter.api.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import it.unimib.datai.nanofaas.modules.offload.oneshot.api.ServiceProfileStore;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
/** Explicit real-process gate. Synthetic D is only a diagnostic fixture, never calibration evidence. */
@Tag("one-shot-e2e")
class OneShotLocalClusterE2eTest {
    static final JsonMapper JSON=JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static final Map<String,Object> INPUT=Map.of("iterations",500000,"working_set_bytes",4096,"seed",7);
    static final Duration PERIOD=Duration.ofMinutes(2);
    static final class Node {
        final String id,namespace,url,managementUrl;final int transport;final Path log;final Process process;
        long generation;
        Node(String id,String namespace,int port,int management,int transport,Path log,Process process) {this.id=id;this.namespace=namespace;url="http://127.0.0.1:"+port;managementUrl="http://127.0.0.1:"+management;this.transport=transport;this.log=log;this.process=process;}
    }
    final Path root=Path.of(System.getProperty("oneShot.repoRoot", "."));
    Path artifacts;String dockerHost,image;final List<Node> nodes=new ArrayList<>();
    static int port() throws Exception {try(var socket=new java.net.ServerSocket(0)) {return socket.getLocalPort();}}
    String command(List<String> args) throws Exception {
        var file=Files.createTempFile(artifacts,"command-",".log");var p=new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true).redirectOutput(file.toFile()).start();
        if(!p.waitFor(90,TimeUnit.SECONDS)) {p.destroyForcibly();throw new AssertionError("command deadline: "+args);}
        String output=Files.readString(file);assertThat(p.exitValue()).as("%s: %s",args,output).isZero();return output.trim();
    }
    Node start(String id,boolean edge,int seed) throws Exception {
        int http=port(),management=port(),transport=port();var log=artifacts.resolve(id+".log");var args=new ArrayList<String>();
        String binary=System.getProperty("oneShot.controlPlaneBinary","");
        if(binary.isBlank()) {args.add(Path.of(System.getProperty("java.home"),"bin","java").toString());args.addAll(List.of("-Xmx256m","-XX:+UseSerialGC","-jar",root.resolve("platform/control-plane/build/libs/app.jar").toString()));}
        else {assertThat(Files.isExecutable(Path.of(binary))).as("native executable prerequisite").isTrue();args.add(binary);}
        String namespace=artifacts.getFileName()+"-"+id;
        args.addAll(List.of("--server.port="+http,"--management.server.port="+management,"--logging.level.root=WARN","--nanofaas.registry.path="+artifacts.resolve(id+"-functions.json"),"--nanofaas.deployment.default-backend=container-local","--nanofaas.container-local.namespace="+namespace,"--nanofaas.container-local.runtime-adapter="+(id.equals("b")?"docker-java":"docker"),"--nanofaas.container-local.readiness-timeout=4s","--nanofaas.container-local.readiness-poll-interval=100ms","--nanofaas.p2p.enabled="+edge,"--nanofaas.p2p.admin.enabled="+edge,"--nanofaas.p2p.node-id="+id,"--nanofaas.p2p.port="+transport,"--nanofaas.p2p.external-host=127.0.0.1","--nanofaas.p2p.invocation-uri=http://127.0.0.1:"+http,"--nanofaas.p2p.ping-interval=250ms","--nanofaas.p2p.ping-timeout=500ms","--nanofaas.forecasting.enabled="+edge,"--nanofaas.forecasting.node-id="+id,"--nanofaas.forecasting.provider=ORACLE","--nanofaas.forecasting.window=1s","--nanofaas.forecasting.max-age=5m","--nanofaas.offload.one-shot.enabled="+edge,"--nanofaas.offload.one-shot.auction-budget=5s","--nanofaas.offload.one-shot.peer-timeout=500ms","--nanofaas.offload.one-shot.solver-budget=1s","--nanofaas.offload.one-shot.preparation-budget=5s","--nanofaas.offload.one-shot.clock-threshold=100ms","--nanofaas.offload.one-shot.clock-max-age=5m","--spring.autoconfigure.exclude=it.unimib.datai.nanofaas.modules.asyncqueue.AsyncQueueConfiguration,it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueConfiguration,it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueRuntimeConfigAutoConfiguration,it.unimib.datai.nanofaas.modules.runtimeconfig.RuntimeConfigConfiguration","--sync-queue.enabled=false"));
        if(seed>0) args.add("--nanofaas.p2p.seeds[0]=127.0.0.1:"+seed);
        var builder=new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());builder.environment().put("DOCKER_HOST",dockerHost);
        var node=new Node(id,namespace,http,management,transport,log,builder.start());nodes.add(node);
        await(45,()->{if(!node.process.isAlive()) throw new AssertionError(Files.readString(node.log));return get(node,"/v1/functions").statusCode()==200;});return node;
    }
    static HttpResponse<String> request(Node node,String path,String method,Object body,Map<String,String> headers) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(node.url+path)).timeout(Duration.ofSeconds(30));headers.forEach(builder::header);
        if(body==null) builder.method(method,HttpRequest.BodyPublishers.noBody());else builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
        return HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> get(Node node,String path) throws Exception {return request(node,path,"GET",null,Map.of());}
    static JsonNode json(HttpResponse<String> response,int status) {assertThat(response.statusCode()).as(response.body()).isEqualTo(status);return JSON.readTree(response.body());}
    interface Condition {boolean get() throws Exception;}
    static void await(int seconds,Condition condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);Exception last=null;
        while(System.nanoTime()<deadline) {try {if(condition.get()) return;}catch(Exception e) {last=e;}Thread.sleep(100);}
        throw new AssertionError("bounded wait expired",last);
    }
    void register(Node node,boolean cloud) throws Exception {
        var spec=new LinkedHashMap<String,Object>();spec.put("name","f");spec.put("image",image);spec.put("executionMode","DEPLOYMENT");spec.put("runtimeMode","HTTP");spec.put("timeoutMs",5000);spec.put("concurrency",1);spec.put("queueSize",10);spec.put("maxRetries",0);spec.put("env",Map.of("NANOFAAS_ONE_SHOT_PROFILE","true","NANOFAAS_MAX_CONCURRENT_HANDLERS","1"));spec.put("resources",Map.of("limits",Map.of("cpu",1,"memoryMiB",128)));spec.put("scalingConfig",Map.of("strategy","NONE","minReplicas",cloud?1:0,"maxReplicas",1,"concurrencyControl",Map.of("mode","STATIC_PER_POD","targetInFlightPerPod",1)));
        json(request(node,"/v1/functions","POST",spec,Map.of()),201);
        if(!cloud) node.generation=json(get(node,"/v1/admin/offload/one-shot/status"),200).path("catalogGenerations").path("f").asLong();
        assertThat(cloud || node.generation>0).isTrue();
    }
    Map<String,Object> profile(Node node) {
        var fn=new LinkedHashMap<String,Object>();fn.put("function","f");fn.put("imageDigest",image);fn.put("runtime","HTTP");fn.put("backend","container-local");fn.put("inputHash",ServiceProfileStore.hash(JSON.writeValueAsBytes(INPUT)));fn.put("cpuQuota",1);fn.put("memoryMiB",128);fn.put("replicas",1);fn.put("coLocation",List.of());fn.put("serviceSeconds",.5);fn.put("statistics",Map.of("sampleCount",1,"meanSeconds",.5,"stddevSeconds",0,"p95Seconds",.5));fn.put("validity",Map.of("minReplicas",1,"maxReplicas",1,"maxRelativeCapacityError",0));fn.put("measurement",Map.of("warmupInvocations",0,"includesColdStarts",false,"occupancy","physical-handler","rawSamplesHash","sha256:"+"0".repeat(64)));
        return Map.of("schemaVersion",1,"profileId","cluster","provider","synthetic-local-test","purpose","workflow-validation","synthetic",true,"sourceCommit","0".repeat(40),"environmentFingerprint","sha256:"+"a".repeat(64),"environment",Map.of("host","local-test","vm","docker","os","linux","architecture",System.getProperty("os.arch"),"cpu","synthetic-fixture"),"functions",List.of(fn));
    }
    void configure(Node node,Node cloud) throws Exception {
        var profile=profile(node);json(request(node,"/v1/admin/offload/one-shot/profiles/cluster","PUT",profile,Map.of("If-Match","0","X-Content-SHA256",ServiceProfileStore.hash(JSON.writeValueAsBytes(profile)))),200);
        var config=new LinkedHashMap<String,Object>();config.put("schemaVersion",1);config.put("profileId","cluster");config.put("environmentFingerprint","sha256:"+"a".repeat(64));config.put("purpose","workflow-validation");config.put("allowSynthetic",true);config.put("cloudUri",cloud.url);config.put("memoryCapacityMiB",128);config.put("flowQuantum",1);config.put("period",PERIOD.toString());config.put("leadTime","PT15S");config.put("scheduled",false);config.put("anchor",Instant.EPOCH.toString());config.put("preparationBudget","PT5S");config.put("maxOperationalFraction",.1);config.put("burst",1);config.put("maxSolverStates",2000000);config.put("maxSolverBytes",67108864);config.put("functions",Map.of("f",Map.of("generation",node.generation,"imageDigest",image,"inputHash",ServiceProfileStore.hash(JSON.writeValueAsBytes(INPUT)),"utilization",.8,"alpha",1,"delta",.9,"gamma",.1)));config.put("negotiation",Map.of("auctionBudget","PT5S","peerTimeout","PT0.5S","solverBudget","PT1S","maxRounds",20,"parallelism",4,"queueCapacity",64,"maxPeers",16,"maxAuctionFraction",.1));
        json(request(node,"/v1/admin/offload/one-shot/config","PUT",config,Map.of("If-Match","0")),200);
        json(request(node,"/v1/admin/offload/one-shot/clock-health","PUT",Map.of("offset","PT0S","measuredAt",Instant.now().toString()),Map.of()),200);
    }
    List<JsonNode> prepare(List<Node> edges,long epoch,Instant from,double[] loads,long revision) throws Exception {
        for(int i=0;i<edges.size();i++) {var node=edges.get(i);json(request(node,"/v1/admin/forecasting/trace","PUT",Map.of("schemaVersion",1,"nodeId",node.id,"revision",revision,"provider","oracle","producedAt",Instant.now().toString(),"entries",List.of(Map.of("function","f","generation",node.generation,"start",from.toString(),"end",from.plus(PERIOD).toString(),"rate",loads[i],"unit","requests/s"))),Map.of("If-Match",Long.toString(revision-1))),200);}
        var pending=new ArrayList<CompletableFuture<JsonNode>>();
        for(var node:edges) pending.add(CompletableFuture.supplyAsync(()->{try {return json(request(node,"/v1/admin/offload/one-shot/epochs/"+epoch+"/prepare","POST",Map.of("startsAt",from.toString(),"endsAt",from.plus(PERIOD).toString()),Map.of()),200);}catch(Exception e) {throw new CompletionException(e);}}));
        var results=new ArrayList<JsonNode>();for(int i=0;i<pending.size();i++) {var result=pending.get(i).get(25,TimeUnit.SECONDS);Files.writeString(artifacts.resolve("epoch-"+epoch+"-"+edges.get(i).id+".json"),result.toPrettyString());results.add(result);}return results;
    }
    Map<String,Integer> traffic(Node origin,int count,String prefix) throws Exception {
        var counts=new HashMap<String,Integer>();var ids=new HashSet<String>();
        for(int i=0;i<count;i++) {
            var result=request(origin,"/v1/functions/f:invoke","POST",Map.of("input",INPUT),Map.of("Idempotency-Key",prefix+i));var body=json(result,200);Files.writeString(artifacts.resolve(prefix+i+"-response.json"),body.toPrettyString());assertThat(body.path("status").asString()).as(body.toPrettyString()).isEqualTo("success");assertThat(ids.add(body.path("executionId").asString())).isTrue();
            String node=result.headers().firstValue("X-NanoFaaS-Execution-Node").orElseThrow();counts.merge(node,1,Integer::sum);
            var replay=request(origin,"/v1/functions/f:invoke","POST",Map.of("input",INPUT),Map.of("Idempotency-Key",prefix+i));assertThat(replay.headers().firstValue("X-NanoFaaS-Execution-Node")).contains(node);assertThat(json(replay,200).path("executionId").asString()).isEqualTo(body.path("executionId").asString());Thread.sleep(200);
        }
        assertThat(counts.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(count);Files.writeString(artifacts.resolve(prefix+"traffic.json"),JSON.writeValueAsString(counts));return counts;
    }
    String container(Node node) throws Exception {
        String id=command(List.of("docker","ps","-aq","--filter","label=io.nanofaas.function="+node.namespace+"/f"));
        assertThat(id).as("exactly one scoped physical replica on %s",node.id).matches("[0-9a-f]{12,64}");return id;
    }
    void verifyPhysicalExecutionConservation(int expected) throws Exception {
        long total=0;
        for(var node:nodes) {
            var ports=JSON.readTree(command(List.of("docker","inspect","--format","{{json .NetworkSettings.Ports}}",container(node))));
            int sdkPort=ports.path("8080/tcp").get(0).path("HostPort").asInt();
            var response=HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+sdkPort+"/metrics")).timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);Files.writeString(artifacts.resolve(node.id+"-runtime-metrics.txt"),response.body());
            var count=java.util.regex.Pattern.compile("(?m)^nanofaas_runtime_replica_occupancy_seconds_count (\\d+)$").matcher(response.body());
            assertThat(count.find()).as("physical occupancy samples on %s",node.id).isTrue();total+=Long.parseLong(count.group(1));
            if(node.process.isAlive()) {
                var metrics=HTTP.send(HttpRequest.newBuilder(URI.create(node.managementUrl+"/actuator/prometheus")).timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.ofString());assertThat(metrics.statusCode()).isEqualTo(200);
                Files.writeString(artifacts.resolve(node.id+"-control-metrics.txt"),metrics.body());
                if(!node.id.equals("cloud")) assertThat(metrics.body()).contains("nanofaas_oneshot_solver_seconds_count","nanofaas_oneshot_auction_seconds_count","nanofaas_oneshot_operational_seconds_count 1");
            }
        }
        assertThat(total).as("unique logical invocations equal physical completions; replays add none").isEqualTo(expected);
        Files.writeString(artifacts.resolve("conservation.json"),JSON.writeValueAsString(Map.of("logicalExecutions",expected,"physicalCompletions",total)));
    }
    @Test @Timeout(value=5,unit=TimeUnit.MINUTES) void fullAuctionReadinessRoutingAndFaultedNextEpoch() throws Exception {
        var evidence=root.resolve("platform/modules/offload/build/test-diagnostics");Files.createDirectories(evidence);artifacts=Files.createTempDirectory(evidence,"nf-one-shot-");
        try {
            dockerHost=command(List.of("docker","context","inspect","--format","{{.Endpoints.docker.Host}}"));command(List.of("docker","info","--format","{{.ServerVersion}}"));
            image=command(List.of("docker","image","inspect","--format","{{.Id}}",System.getProperty("oneShot.workloadImage")));assertThat(image).matches("sha256:[0-9a-f]{64}");
            var cloud=start("cloud",false,0);var a=start("a",true,0);var b=start("b",true,a.transport);var c=start("c",true,a.transport);var edges=List.of(a,b,c);
            assertThat(get(cloud,"/v1/admin/offload/one-shot/status").statusCode()).isEqualTo(404);
            assertThat(get(cloud,"/v1/admin/forecasting/trace").statusCode()).isEqualTo(404);
            register(cloud,true);for(var n:edges) {register(n,false);configure(n,cloud);}
            await(20,()->{for(var n:edges) {var peers=json(get(n,"/v1/admin/p2p/peers"),200);long active=java.util.stream.StreamSupport.stream(peers.spliterator(),false).filter(p->p.path("active").asBoolean()).count();if(active<2 || json(get(n,"/v1/admin/offload/one-shot/status"),200).path("peerEndpoints").size()!=2) return false;}return true;});
            var from=Instant.now().plusSeconds(15);var first=prepare(edges,1,from,new double[]{6,0,0},1);assertThat(first).allSatisfy(p->assertThat(p.path("status").asString()).as(p.toPrettyString()).isEqualTo("PREPARED"));
            await(20,()->!Instant.now().isBefore(from));var counts=traffic(a,18,"first-");assertThat(counts.keySet()).contains("a","b","c","cloud");
            var secondFrom=from.plus(PERIOD);String paused=container(c);
            command(List.of("docker","pause",paused));List<JsonNode> second;
            try {
                // The public replica API forces a provider reading, so the injected
                // fault is observed before starting the next auction (not hidden by TTL).
                await(10,()->json(get(c,"/v1/functions/f/replicas"),200).path("readyReplicas").asInt()==0);
                second=prepare(edges,2,secondFrom,new double[]{0,6,0},2);
            }finally {command(List.of("docker","unpause",paused));}
            assertThat(second.get(2).path("readyReplicas").path("f").asInt()).isZero();assertThat(second.get(2).path("status").asString()).isEqualTo("DEGRADED");
            await(130,()->!Instant.now().isBefore(secondFrom));a.process.destroyForcibly();assertThat(a.process.waitFor(5,TimeUnit.SECONDS)).isTrue();
            await(30,()->{var peers=json(get(b,"/v1/admin/p2p/peers"),200);return java.util.stream.StreamSupport.stream(peers.spliterator(),false).noneMatch(p->p.path("id").asString().equals("a") && p.path("active").asBoolean());});
            var faulted=traffic(b,12,"second-");assertThat(faulted.keySet()).contains("b","cloud").doesNotContain("a","c");
            verifyPhysicalExecutionConservation(30);
            for(var n:List.of(b,c)) {var events=json(get(n,"/v1/admin/offload/one-shot/epochs/2/events"),200);Files.writeString(artifacts.resolve(n.id+"-events.json"),events.toPrettyString());}
            var events=json(get(c,"/v1/admin/offload/one-shot/epochs/2/events"),200);assertThat(events.path("entries").get(0).path("event").path("censored").asBoolean()).isTrue();
        } finally {
            for(var n:nodes) if(n.process.isAlive()) {n.process.destroy();if(!n.process.waitFor(10,TimeUnit.SECONDS)) n.process.destroyForcibly();}
            for(var n:nodes) {try {var containers=command(List.of("docker","ps","-aq","--filter","label=io.nanofaas.function="+n.namespace+"/f"));if(!containers.isBlank()) {var args=new ArrayList<>(List.of("docker","rm","-f"));args.addAll(Arrays.asList(containers.split("\\s+")));command(args);}}catch(Exception e) {System.err.println("scoped cleanup failure: "+e);}}
            System.out.println("One-shot cluster evidence (diagnostic, not Azure calibration): "+artifacts);
        }
    }
}
