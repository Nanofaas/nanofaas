# Report only: shrinking runs in memory, with no output JAR or changed build artifacts.
-dontoptimize
-dontobfuscate
-dontpreverify
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Record

# Exact, reviewed exclusions with documented source/test/framework callers.
-include reviewed-usage.pro

# Executable entry points across the control plane, CLI and example functions.
-keepclasseswithmembers class * { public static void main(java.lang.String[]); }

# SDKs and the function contract have consumers outside this repository.
-keep,includedescriptorclasses public class it.unimib.datai.nanofaas.sdk.** { public protected *; }
-keep,includedescriptorclasses public interface it.unimib.datai.nanofaas.common.runtime.FunctionHandler { *; }
-keep,includedescriptorclasses class * implements it.unimib.datai.nanofaas.common.runtime.FunctionHandler { public *; }
-keep,includedescriptorclasses @it.unimib.datai.nanofaas.sdk.NanofaasFunction class * { public *; }

# Spring stereotypes can be meta-annotations: spell out the framework entry points.
-keep,includedescriptorclasses @org.springframework.stereotype.Component class * { <init>(...); }
-keep,includedescriptorclasses @org.springframework.stereotype.Service class * { <init>(...); }
-keep,includedescriptorclasses @org.springframework.stereotype.Repository class * { <init>(...); }
-keep,includedescriptorclasses @org.springframework.stereotype.Controller class * { <init>(...); }
-keep,includedescriptorclasses @org.springframework.web.bind.annotation.RestController class * { <init>(...); }
-keep,includedescriptorclasses @org.springframework.context.annotation.Configuration class * { <init>(...); }
-keep,includedescriptorclasses @org.springframework.boot.autoconfigure.AutoConfiguration class * { <init>(...); }
-keep,includedescriptorclasses @org.springframework.boot.autoconfigure.SpringBootApplication class * { <init>(...); }
-keep @org.springframework.boot.context.properties.ConfigurationProperties class * { *; }
-keep class * implements org.springframework.aot.hint.RuntimeHintsRegistrar { public *; }
-keepclassmembers,includedescriptorclasses class * {
    @org.springframework.context.annotation.Bean <methods>;
    @org.springframework.context.event.EventListener <methods>;
    @org.springframework.scheduling.annotation.Scheduled <methods>;
    @org.springframework.beans.factory.annotation.Autowired *;
    @org.springframework.beans.factory.annotation.Value *;
    @jakarta.annotation.PostConstruct <methods>;
    @jakarta.annotation.PreDestroy <methods>;
    @org.springframework.web.bind.annotation.RequestMapping <methods>;
    @org.springframework.web.bind.annotation.GetMapping <methods>;
    @org.springframework.web.bind.annotation.PostMapping <methods>;
    @org.springframework.web.bind.annotation.PutMapping <methods>;
    @org.springframework.web.bind.annotation.PatchMapping <methods>;
    @org.springframework.web.bind.annotation.DeleteMapping <methods>;
    @org.springframework.web.bind.annotation.ExceptionHandler <methods>;
    @org.springframework.web.bind.annotation.InitBinder <methods>;
    @org.springframework.web.bind.annotation.ModelAttribute *;
    public void close();
    public void shutdown();
    public void dispose();
    public *** shutdownNow();
}
# A named Spring destroyMethod, rather than a source-level caller.
-keepclassmembers class it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore {
    public void clear();
}
-keep @org.springframework.web.bind.annotation.ControllerAdvice class * { <init>(...); }
-keep @org.springframework.web.bind.annotation.RestControllerAdvice class * { <init>(...); }

# Jackson, records and enums: preserve binding members only when the class is reachable.
-keepclassmembers,includedescriptorclasses class * extends java.lang.Record { <init>(...); public <methods>; }
-keepclassmembers enum * { *; }
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.** {
    public <init>(...);
    public <fields>;
    public *** get*();
    public boolean is*();
    public void set*(***);
    @com.fasterxml.jackson.annotation.JsonCreator *;
    @com.fasterxml.jackson.annotation.JsonProperty *;
    @com.fasterxml.jackson.annotation.JsonValue *;
    @com.fasterxml.jackson.annotation.JsonAnyGetter *;
    @com.fasterxml.jackson.annotation.JsonAnySetter *;
    @com.fasterxml.jackson.annotation.JsonSetter *;
}

# Picocli discovers command objects and fields through reflection.
-keep @picocli.CommandLine$Command class * { <init>(...); }
-keepclassmembers class * {
    @picocli.CommandLine$Option *;
    @picocli.CommandLine$Parameters *;
    @picocli.CommandLine$Mixin *;
    @picocli.CommandLine$ParentCommand *;
    @picocli.CommandLine$Spec *;
}
