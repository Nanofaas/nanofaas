package it.unimib.datai.nanofaas.services.warmecho;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class WarmEchoApplicationTest {
    @Test
    void issue015_contextLoads() {
    }

    @Test
    void main_startsApplication() {
        WarmEchoApplication.main(new String[]{"--server.port=0"});
    }
}
