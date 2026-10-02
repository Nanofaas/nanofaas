package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RttWindowTest {
    @Test
    void emptyHasNoMedian() {
        assertThat(new RttWindow(5).median()).isNull();
    }

    @Test
    void medianOfOddAndEven() {
        var w = new RttWindow(5);
        w.add(10); w.add(30); w.add(20);
        assertThat(w.median()).isEqualTo(20.0);
        w.add(40);
        assertThat(w.median()).isEqualTo(25.0);
    }

    @Test
    void oldestSampleIsEvicted() {
        var w = new RttWindow(3);
        w.add(1000); w.add(10); w.add(10); w.add(10);   // the 1000 outlier falls out
        assertThat(w.median()).isEqualTo(10.0);
    }
}
