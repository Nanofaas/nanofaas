package it.unimib.datai.nanofaas.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FunctionRuntimeApplication {
    public static void main(String[] args) {
        SpringApplication.run(FunctionRuntimeApplication.class, args);
    }
}
