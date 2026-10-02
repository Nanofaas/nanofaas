package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import it.unimib.datai.nanofaas.modules.p2pdiscovery.Vivaldi.Coord;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class VivaldiTest {
    @Test
    void twoNodesConvergeToTheMeasuredRtt() {
        Vivaldi a = new Vivaldi(1), b = new Vivaldi(2);
        for (int i = 0; i < 300; i++) {
            a.update(50, b.coord(), b.error());
            b.update(50, a.coord(), a.error());
        }
        assertThat(a.coord().distanceTo(b.coord())).isCloseTo(50.0, within(3.0));
    }

    @Test
    void triangleEmbedsAllThreeDistances() {
        Vivaldi[] n = {new Vivaldi(1), new Vivaldi(2), new Vivaldi(3)};
        double[][] rtt = {{0, 30, 40}, {30, 0, 50}, {40, 50, 0}};
        for (int it = 0; it < 600; it++)
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++)
                    if (i != j) n[i].update(rtt[i][j], n[j].coord(), n[j].error());
        for (int i = 0; i < 3; i++)
            for (int j = i + 1; j < 3; j++)
                assertThat(n[i].coord().distanceTo(n[j].coord())).isCloseTo(rtt[i][j], within(6.0));
    }

    @Test
    void coincidentNodesAreSeparatedNotNaN() {
        Vivaldi a = new Vivaldi(1);
        a.update(40, new Coord(0, 0, 0), 1.0);   // both at origin: direction must be chosen, not NaN
        assertThat(Double.isNaN(a.coord().x())).isFalse();
        assertThat(a.coord().distanceTo(new Coord(0, 0, 0))).isGreaterThan(0);
    }

    @Test
    void nonPositiveRttIsIgnored() {
        Vivaldi a = new Vivaldi(1);
        Coord before = a.coord();
        a.update(0, new Coord(1, 1, 1), 1.0);
        a.update(-5, new Coord(1, 1, 1), 1.0);
        assertThat(a.coord()).isEqualTo(before);
    }

    @Test
    void coordRoundTripsThroughAList() {
        Coord c = new Coord(0.3, 1.1, 0.02);
        assertThat(Coord.fromList(c.toList())).isEqualTo(c);
        assertThat(Coord.fromList(null)).isEqualTo(new Coord(0, 0, 0));
        assertThat(Coord.fromList(List.of(1.0))).isEqualTo(new Coord(0, 0, 0));
    }

    @Test
    void nonFiniteCoordinatesFromAListOrAFileBecomeTheOrigin() {
        assertThat(Coord.fromList(List.of(Double.NaN, 0.0, 0.0))).isEqualTo(new Coord(0, 0, 0));
        assertThat(Coord.fromList(List.of(1.0, Double.POSITIVE_INFINITY, 0.0))).isEqualTo(new Coord(0, 0, 0));
        Vivaldi v = new Vivaldi(1);
        v.restore(new Coord(Double.NaN, 0, 0), Double.NaN);
        assertThat(Double.isFinite(v.coord().x())).isTrue();
        assertThat(Double.isFinite(v.error())).isTrue();
    }

    @Test
    void anUpdateThatWouldOverflowIsDiscarded() {
        Vivaldi a = new Vivaldi(1);
        Coord before = a.coord();
        a.update(50, new Coord(1e300, 1e300, 1e300), 0.5);
        assertThat(a.coord()).isEqualTo(before);
        assertThat(Double.isFinite(a.error())).isTrue();
    }
}
