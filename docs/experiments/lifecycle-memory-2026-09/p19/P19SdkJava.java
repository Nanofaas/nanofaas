import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import java.util.*;

@SpringBootConfiguration
@EnableAutoConfiguration
public class P19SdkJava {
    @Bean FunctionHandler measuredHandler(){return request->SdkObservation.handle(request.input());}
    public static void main(String[] args)throws Exception{
        var context=SpringApplication.run(P19SdkJava.class,args);
        SdkObservation.serve(()->{
            Map<String,Object> values=new TreeMap<>();
            for(String name:context.getBeanFactory().getSingletonNames()){
                Object bean=context.getBeanFactory().getSingleton(name);
                if(bean!=null && bean.getClass().getName().startsWith("it.unimib.datai.nanofaas.sdk"))
                    values.put(name,SdkObservation.fields(bean));
            }
            return values;
        });
    }
}
