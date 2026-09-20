package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import org.springframework.context.SmartLifecycle;

/**
 * The one Spring lifecycle hook onto the composed engine: start/stop call only
 * {@link SchedulerEngine#start()}/{@link SchedulerEngine#close()}, both already idempotent, so
 * start/stop/start, a partially failed context and a shutdown with pending work each close
 * exactly the worker the engine itself owns.
 */
public final class SchedulerLifecycleAdapter implements SmartLifecycle {

    private final SchedulerEngine engine;
    private volatile boolean running;

    public SchedulerLifecycleAdapter(SchedulerEngine engine) {
        this.engine = engine;
    }

    @Override
    public void start() {
        engine.start();
        running = true;
    }

    @Override
    public void stop() {
        engine.close();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
