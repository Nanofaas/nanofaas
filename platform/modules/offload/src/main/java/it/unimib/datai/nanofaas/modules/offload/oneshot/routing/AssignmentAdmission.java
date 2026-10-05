package it.unimib.datai.nanofaas.modules.offload.oneshot.routing;
import java.util.function.LongSupplier;
/** Accepted quota is never refunded on timeout or transport uncertainty. */
public final class AssignmentAdmission {
    private final double rate;private final int burst;private final LongSupplier time;
    private double tokens;private long last;
    public AssignmentAdmission(double rate,int burst,LongSupplier time) {
        if(!Double.isFinite(rate) || rate<0 || burst<1 || burst>1000) throw new IllegalArgumentException("invalid quota");
        this.rate=rate;this.burst=burst;this.time=time;tokens=rate>0?burst:0;last=time.getAsLong();
    }
    public synchronized boolean tryAdmit() {
        long now=time.getAsLong();if(now>last) {tokens=Math.min(burst,tokens+(now-last)/1e9*rate);last=now;}
        if(rate<=0 || tokens<1) return false;tokens-=1;return true;
    }
}
