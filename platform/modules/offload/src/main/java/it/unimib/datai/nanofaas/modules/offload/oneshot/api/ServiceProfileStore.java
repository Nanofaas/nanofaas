package it.unimib.datai.nanofaas.modules.offload.oneshot.api;
import java.util.*;
import java.security.MessageDigest;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
/** Profiles are content-addressed calibration evidence, not editable runtime estimates. */
public final class ServiceProfileStore {
    public record Environment(String host,String vm,String os,String architecture,String cpu) {
        public Environment { for(var x:new String[]{host,vm,os,architecture,cpu}) text(x); }
    }
    public record Statistics(long sampleCount,double meanSeconds,double stddevSeconds,double p95Seconds) {
        public Statistics { if(sampleCount<1 || !positive(meanSeconds) || !positive(p95Seconds) || !Double.isFinite(stddevSeconds) || stddevSeconds<0) throw new IllegalArgumentException("invalid statistics"); }
    }
    public record Validity(int minReplicas,int maxReplicas,double maxRelativeCapacityError) {
        public Validity { if(minReplicas<0 || maxReplicas<minReplicas || !Double.isFinite(maxRelativeCapacityError) || maxRelativeCapacityError<0) throw new IllegalArgumentException("invalid validity range"); }
    }
    public record Measurement(long warmupInvocations,boolean includesColdStarts,String occupancy,String rawSamplesHash) {
        public Measurement { if(warmupInvocations<0 || includesColdStarts || !"physical-handler".equals(occupancy) || !OneShotSettings.digest(rawSamplesHash)) throw new IllegalArgumentException("physical warm occupancy evidence required"); }
    }
    public record Function(String function,String imageDigest,String runtime,String backend,String inputHash,double cpuQuota,long memoryMiB,int replicas,List<String> coLocation,double serviceSeconds,Statistics statistics,Validity validity,Measurement measurement) {
        public Function {
            text(function);text(runtime);text(backend);
            if(!OneShotSettings.digest(imageDigest) || !OneShotSettings.digest(inputHash) || !positive(cpuQuota) || memoryMiB<1 || replicas<1 || !positive(serviceSeconds) || statistics==null || validity==null || measurement==null || coLocation==null || coLocation.size()>128) throw new IllegalArgumentException("invalid calibration function");
            coLocation=List.copyOf(coLocation);coLocation.forEach(ServiceProfileStore::text);
            if(new HashSet<>(coLocation).size()!=coLocation.size() || replicas<validity.minReplicas() || replicas>validity.maxReplicas()) throw new IllegalArgumentException("invalid measured replica/co-location scope");
        }
    }
    public record Profile(int schemaVersion,String profileId,String provider,String purpose,boolean synthetic,String sourceCommit,String environmentFingerprint,List<Function> functions,Environment environment) {
        public Profile {
            text(profileId);text(provider);
            if(schemaVersion!=1 || !Set.of("workflow-validation","scientific-experiment").contains(purpose) || sourceCommit==null || !sourceCommit.matches("[0-9a-f]{40}") || !OneShotSettings.digest(environmentFingerprint) || functions==null || functions.isEmpty() || functions.size()>128 || environment==null) throw new IllegalArgumentException("invalid profile");
            functions=List.copyOf(functions);
            if(functions.stream().map(Function::function).distinct().count()!=functions.size()) throw new IllegalArgumentException("duplicate calibration function");
        }
    }
    public record Stored(long revision,String contentHash,Profile profile,int bytes) {}
    private final Map<String,Stored> profiles=new HashMap<>();
    private final JsonMapper mapper=JsonMapper.builder(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(256).maxNumberLength(64).build()).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    static void text(String x) { if(x==null || x.isBlank() || x.length()>256) throw new IllegalArgumentException("bounded nonblank identity required"); }
    static boolean positive(double x) { return Double.isFinite(x) && x>0; }
    public static String hash(byte[] bytes) {
        try { return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public synchronized Optional<Stored> get(String id) { return Optional.ofNullable(profiles.get(id)); }
    public synchronized Stored replace(String id,long expected,String digest,byte[] bytes) {
        text(id);
        var old=profiles.get(id);if(expected!=(old==null?0:old.revision())) throw new OneShotConfigurationStore.RevisionConflict();
        if(bytes==null || bytes.length==0 || bytes.length>8*1024*1024 || !hash(bytes).equals(digest)) throw new IllegalArgumentException("profile content hash mismatch or size limit");
        var profile=mapper.readValue(bytes,Profile.class);
        if(!profile.profileId().equals(id)) throw new IllegalArgumentException("profile ID mismatch");
        long total=profiles.values().stream().mapToLong(Stored::bytes).sum()-(old==null?0:old.bytes())+bytes.length;
        if(total>32*1024*1024 || (old==null && profiles.size()>=128)) throw new IllegalArgumentException("profile retention exhausted");
        var stored=new Stored(expected+1,digest,profile,bytes.length);profiles.put(id,stored);return stored;
    }
    public Stored compatible(OneShotSettings settings) {
        var stored=get(settings.profileId()).orElseThrow(()->new IllegalArgumentException("profile missing"));var profile=stored.profile();
        if(!profile.environmentFingerprint().equals(settings.environmentFingerprint()) || !profile.purpose().equals(settings.purpose()) || (profile.synthetic() && (!settings.allowSynthetic() || !"workflow-validation".equals(settings.purpose())))) throw new IllegalArgumentException("profile environment, purpose or synthetic evidence incompatible");
        if(!profile.functions().stream().map(Function::function).collect(java.util.stream.Collectors.toSet()).equals(settings.functions().keySet())) throw new IllegalArgumentException("profile/function scope mismatch");
        return stored;
    }
}
