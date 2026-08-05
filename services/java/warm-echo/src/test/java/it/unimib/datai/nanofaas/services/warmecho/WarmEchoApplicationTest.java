package it.unimib.datai.nanofaas.services.warmecho;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
class WarmEchoApplicationTest {
    @Autowired
    private ApplicationContext context;

    @Test
    void issue015_contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    void main_startsApplication() {
        assertThatCode(() -> WarmEchoApplication.main(new String[]{"--server.port=0"}))
                .doesNotThrowAnyException();
    }
}
