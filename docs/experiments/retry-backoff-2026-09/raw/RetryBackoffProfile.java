package it.unimib.datai.nanofaas;

import java.lang.reflect.Method;

/** Diagnostic only: the three failing arms, unchanged load, longer samples. */
public final class RetryBackoffProfile {
    public static void main(String[] args) throws Exception {
        var profile = SchedulerSwitchBenchmark.Profiles.lowLoad().build();
        var emit = SchedulerSwitchBenchmark.class.getDeclaredMethod("emitSample",
                SchedulerSwitchBenchmark.Profile.class, SchedulerSwitchBenchmark.Arm.class,
                int.class, SchedulerSwitchBenchmark.Run.class);
        emit.setAccessible(true);
        for (var arm : new SchedulerSwitchBenchmark.Arm[] {
                SchedulerSwitchBenchmark.Arm.PER_FUNCTION_NO_CHANGE,
                SchedulerSwitchBenchmark.Arm.SHARED_QUEUE_NO_CHANGE,
                SchedulerSwitchBenchmark.Arm.SHARED_QUEUE_TO_PER_FUNCTION}) {
            profile.spanMs = 8000;
            new SchedulerSwitchBenchmark.Run(profile, arm, 0).execute();
            profile.spanMs = 30000;
            SchedulerSwitchBenchmark.Run.resetSamples();
            var run = new SchedulerSwitchBenchmark.Run(profile, arm, 1);
            run.execute();
            emit.invoke(null, profile, arm, 1, run);
        }
    }
}
