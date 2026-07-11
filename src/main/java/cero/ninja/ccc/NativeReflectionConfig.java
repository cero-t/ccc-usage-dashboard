package cero.ninja.ccc;

import cero.ninja.ccc.http.DashboardApi;
import cero.ninja.ccc.jobs.AnnotateJob;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(targets = {
        AnnotateJob.RawRow.class,
        DashboardApi.Config.class,
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
        DashboardApi.SeriesPoint.class,
        DashboardApi.CostByModel.class,
        DashboardApi.CostByTrigger.class,
        DashboardApi.CostByModelTrigger.class,
        DashboardApi.TokenByModel.class,
        DashboardApi.TokenByTrigger.class,
        DashboardApi.TokenByModelTrigger.class,
        DashboardApi.CostSeriesPoint.class,
        DashboardApi.TokenSeriesPoint.class,
        DashboardApi.ConversationUsage.class
})
final class NativeReflectionConfig {
    private NativeReflectionConfig() {}
}
