package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;

/** Real per-function proxy fixture; its backend endpoints are independent loopback HTTP servers. */
public class P19ProxyProfile {
    public static void main(String[] args)throws Exception{
        var proxy=new RoundRobinFunctionProxy("127.0.0.1",2,Duration.ofSeconds(2),null,
                new ContainerProxyProperties(1048576,8388608,16777216,Duration.ofMillis(300),Duration.ofMillis(500)));
        var backends=List.of("http://127.0.0.1:19083","http://127.0.0.1:19085","http://127.0.0.1:19086");
        proxy.updateBackends(backends);
        var fields=Class.forName("SdkObservation").getMethod("fields",Object.class);
        var json=Class.forName("P19Probe").getDeclaredMethod("json",Object.class);json.setAccessible(true);
        HttpServer control=HttpServer.create(new InetSocketAddress("127.0.0.1",19082),8);
        control.createContext("/",e->{
            try{
                String action=e.getRequestURI().getPath();
                if(action.equals("/rotate")){
                    Collections.rotate(new ArrayList<>(backends),1);
                    proxy.updateBackends(backends.reversed());
                }else if(action.equals("/close"))proxy.close();
                @SuppressWarnings("unchecked") Map<String,Object> values=(Map<String,Object>)fields.invoke(null,proxy);
                values.put("endpoint",proxy.endpointUrl());
                values.put("selected_destinations",backends);
                values.put("in_flight",proxy.snapshot().inFlight());
                values.put("buffered_bytes",proxy.snapshot().bufferedBytes());
                byte[] data=((String)json.invoke(null,values)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);
            }catch(Exception failure){e.sendResponseHeaders(500,-1);}finally{e.close();}
        });
        Runtime.getRuntime().addShutdownHook(new Thread(()->{proxy.close();control.stop(0);}));
        control.start();
    }
}
