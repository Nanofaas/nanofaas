import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.ControlPlaneApplication;
import it.unimib.datai.nanofaas.controlplane.deployment.*;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Existing managed-provider seam: only provider I/O is controllably blocked, all plane ownership is real. */
public class P19T6Profile {
    static final BlockingProvider provider=new BlockingProvider();
    @Configuration(proxyBeanMethods=false)
    static class Fixture {@Bean ManagedDeploymentProvider measuredProvider(){return provider;}}
    public static void main(String[] args)throws Exception{
        var context=SpringApplication.run(new Class<?>[]{ControlPlaneApplication.class,Fixture.class},args);
        var field=P19Probe.class.getDeclaredField("context");field.setAccessible(true);field.set(null,context);
        var snapshot=P19Probe.class.getDeclaredMethod("snapshot");snapshot.setAccessible(true);
        SdkObservation.serve(()->{
            try {
                @SuppressWarnings("unchecked") Map<String,Object> values=(Map<String,Object>)snapshot.invoke(null);
                values.put("provider",Map.of("active_reads",provider.active.get(),"blocked_reads",provider.blockedReads.get(),
                        "healthy_reads",provider.healthyReads.get(),"scale_calls",provider.scales.get(),"names",provider.ready.size()));
                return values;
            }catch(Exception e){throw new RuntimeException(e);}
        });
        HttpServer control=HttpServer.create(new InetSocketAddress("127.0.0.1",19084),8);
        control.createContext("/",e->{
            String action=e.getRequestURI().getPath();
            if(action.equals("/block"))provider.block=true;
            else if(action.equals("/release")){provider.block=false;provider.release.countDown();}
            else if(action.equals("/invalidate"))context.getBean(ReplicaStatusSnapshot.class).invalidate("blocked");
            else {e.sendResponseHeaders(404,-1);e.close();return;}
            e.sendResponseHeaders(204,-1);e.close();
        });
        Runtime.getRuntime().addShutdownHook(new Thread(()->{provider.release.countDown();control.stop(0);}));
        control.start();
    }
    static class BlockingProvider implements ManagedDeploymentProvider {
        final Map<String,Integer> ready=new ConcurrentHashMap<>();
        final AtomicInteger active=new AtomicInteger(),blockedReads=new AtomicInteger(),healthyReads=new AtomicInteger(),scales=new AtomicInteger();
        final CountDownLatch release=new CountDownLatch(1);
        volatile boolean block;
        public String backendId(){return "p19-measured";}
        public boolean isAvailable(){return true;}
        public boolean supports(FunctionSpec spec){return true;}
        public ProvisionResult provision(FunctionSpec spec){
            ready.put(spec.name(),spec.name().equals("blocked")?0:1);
            return new ProvisionResult("http://127.0.0.1:19083/",backendId(),ExecutionMode.DEPLOYMENT,null,Map.of());
        }
        public ProvisionResult reconcile(FunctionSpec spec,int replicas,Map<String,String> objects){return provision(spec);}
        public void deprovision(String name){ready.remove(name);}
        public void setReplicas(String name,int replicas){scales.incrementAndGet();ready.computeIfPresent(name,(k,v)->replicas);}
        public int getReadyReplicas(String name){return getReplicaStatus(name).readyReplicas();}
        public ReplicaStatus getReplicaStatus(String name){
            int before=ready.getOrDefault(name,0);
            active.incrementAndGet();
            try{
                if(name.equals("blocked")&&block){
                    blockedReads.incrementAndGet();
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                    while(release.getCount()!=0&&System.nanoTime()<deadline){
                        try{release.await(20,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}
                    }
                }else healthyReads.incrementAndGet();
                return new ReplicaStatus(before,before);
            }finally{active.decrementAndGet();}
        }
    }
}
