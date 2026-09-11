import it.unimib.datai.nanofaas.sdk.lite.NanofaasRuntime;

public class P19SdkLite {
    public static void main(String[] args)throws Exception{
        var runtime=NanofaasRuntime.builder().port(19080).functionName("p19-lite")
                .handler(request->SdkObservation.handle(request.input())).build();
        SdkObservation.serve(()->SdkObservation.fields(runtime));
        runtime.start();
    }
}
