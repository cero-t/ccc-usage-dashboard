package cero.ninja.agent.codexusage;

import cero.ninja.agent.codexusage.http.DashboardApi;
import cero.ninja.agent.codexusage.jobs.AnnotateJob;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(targets = {
        AnnotateJob.RawRow.class,
        DashboardApi.Summary.class,
        DashboardApi.UsageLatest.class,
        DashboardApi.ModelCredits.class,
        DashboardApi.CreditPoint.class,
        DashboardApi.RecentEvent.class,
        DashboardApi.ErrorEvent.class,
        DashboardApi.RawEvent.class,
        DashboardApi.UsagePoint.class,
        DashboardApi.TriggerCredits.class,
        DashboardApi.ModelTriggerCredits.class,
        DashboardApi.SeriesPoint.class
})
final class NativeReflectionConfig {
    private NativeReflectionConfig() {}
}
