package it.unimib.datai.nanofaas.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class FunctionRuntimeApplicationTest {
    @Test
    void issue015_contextLoads() {
    }

    @Test
    void main_startsApplication() {
        FunctionRuntimeApplication.main(new String[]{"--server.port=0"});
    }
}
