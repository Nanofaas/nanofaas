package it.unimib.datai.nanofaas.controlplane.offload;
import java.util.*;
/** Trusted raw HTTP metadata. Any hop-marker presence prevents a second forwarding hop. */
public record OffloadContext(boolean offloadedHop,String traceparent,String tracestate,Metadata metadata,boolean invalidMetadata) {
    public record Metadata(String origin,String originIncarnation,long epoch,String assignment) {}
    public OffloadContext(boolean hop,String parent,String state) { this(hop,parent,state,null,false); }
    private static final OffloadContext NONE=new OffloadContext(false,null,null);
    public static OffloadContext none() { return NONE; }
    public static OffloadContext fromHttp(String hop,String parent,String state,Map<String,? extends List<String>> headers) {
        var nativeHeaders=new HashMap<String,List<String>>();boolean hopPresent=hop!=null;int hopValues=0;
        for(var e:headers.entrySet()) {
            String key=e.getKey().toLowerCase(Locale.ROOT);
            if(key.equals("x-nanofaas-offload-hop")) {hopPresent=true;hopValues+=e.getValue().size();}
            else if(key.startsWith("x-nanofaas-offload-")) nativeHeaders.merge(key,List.copyOf(e.getValue()),(a,b)->{var merged=new ArrayList<>(a);merged.addAll(b);return merged;});
        }
        if(nativeHeaders.isEmpty()) return new OffloadContext(hopPresent,parent,state);
        try {
            if(hopValues>1 || !"1".equals(hop) || nativeHeaders.size()!=4 || !"1".equals(single(nativeHeaders,"version"))) throw new IllegalArgumentException();
            var origin=single(nativeHeaders,"origin");int split=origin.lastIndexOf('@');if(split<1 || split==origin.length()-1 || origin.length()>513) throw new IllegalArgumentException();
            var epoch=single(nativeHeaders,"epoch");if(!epoch.matches("[0-9]{1,19}")) throw new IllegalArgumentException();
            long id=Long.parseLong(epoch);var assignment=single(nativeHeaders,"assignment");if(assignment.isBlank() || assignment.length()>256) throw new IllegalArgumentException();
            return new OffloadContext(true,parent,state,new Metadata(origin.substring(0,split),origin.substring(split+1),id,assignment),false);
        } catch(RuntimeException invalid) { return new OffloadContext(true,parent,state,null,true); }
    }
    private static String single(Map<String,List<String>> headers,String name) { var values=headers.get("x-nanofaas-offload-"+name);if(values==null || values.size()!=1 || values.getFirst()==null) throw new IllegalArgumentException();return values.getFirst(); }
}
