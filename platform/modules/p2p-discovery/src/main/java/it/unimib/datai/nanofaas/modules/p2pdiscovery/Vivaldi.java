package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import java.util.List;
import java.util.Random;

/** Vivaldi network coordinates. The only mutable state is guarded by this instance's monitor. */
public final class Vivaldi {
    private static final double CC = 0.25;   // timestep constant
    private static final double CE = 0.25;   // error-weight constant
    private static final double MAX_ERROR = 1.0;

    public record Coord(double x, double y, double z) {
        public double distanceTo(Coord o) {
            double dx = x - o.x;
            double dy = y - o.y;
            double dz = z - o.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        public List<Double> toList() {
            return List.of(x, y, z);
        }

        public static Coord fromList(List<Double> l) {
            if (l == null || l.size() < 3 || l.stream().anyMatch(v -> v == null || !Double.isFinite(v))) {
                return new Coord(0, 0, 0);
            }
            return new Coord(l.get(0), l.get(1), l.get(2));
        }
    }

    private final Random random;
    private Coord coord = new Coord(0, 0, 0);
    private double error = MAX_ERROR;

    public Vivaldi(long seed) {
        this.random = new Random(seed);
    }

    public synchronized Coord coord() {
        return coord;
    }

    public synchronized double error() {
        return error;
    }

    public synchronized void restore(Coord c, double err) {
        boolean usable = Double.isFinite(c.x()) && Double.isFinite(c.y()) && Double.isFinite(c.z()) && Double.isFinite(err);
        this.coord = usable ? c : new Coord(0, 0, 0);
        this.error = usable ? Math.clamp(err, 0.0, MAX_ERROR) : MAX_ERROR;
    }

    public synchronized void update(double rttMs, Coord remote, double remoteError) {
        if (Double.isNaN(rttMs) || rttMs <= 0) {
            return;
        }
        double w = error / Math.max(error + remoteError, 1e-9);
        double dist = coord.distanceTo(remote);
        double sampleError = Math.abs(dist - rttMs) / rttMs;
        double newError = Math.clamp(sampleError * CE * w + error * (1 - CE * w), 0.0, MAX_ERROR);
        double[] dir = unit(coord, remote, dist);
        double force = CC * w * (rttMs - dist);
        Coord next = new Coord(coord.x() + force * dir[0], coord.y() + force * dir[1], coord.z() + force * dir[2]);
        if (!Double.isFinite(newError) || !Double.isFinite(next.x()) || !Double.isFinite(next.y()) || !Double.isFinite(next.z())) {
            return;   // an overflowing update must not poison the state for good
        }
        error = newError;
        coord = next;
    }

    /** Unit vector from remote towards local; random when the two coincide. */
    private double[] unit(Coord local, Coord remote, double dist) {
        if (dist > 1e-9) {
            return new double[]{(local.x() - remote.x()) / dist, (local.y() - remote.y()) / dist,
                    (local.z() - remote.z()) / dist};
        }
        double x = random.nextGaussian();
        double y = random.nextGaussian();
        double z = random.nextGaussian();
        double n = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / n, y / n, z / n};
    }
}
