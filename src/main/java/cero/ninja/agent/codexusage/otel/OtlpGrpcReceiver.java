package cero.ninja.agent.codexusage.otel;

import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.MetricsServiceGrpc;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import io.quarkus.grpc.GrpcService;
import jakarta.inject.Inject;

@GrpcService
public class OtlpGrpcReceiver extends TraceServiceGrpc.TraceServiceImplBase
        implements MetricsServiceGrpc.AsyncService, LogsServiceGrpc.AsyncService {

    @Inject
    RawLogStore rawLogStore;

    @Override
    public void export(
            ExportTraceServiceRequest request,
            StreamObserver<ExportTraceServiceResponse> responseObserver
    ) {
        responseObserver.onNext(ExportTraceServiceResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void export(
            ExportMetricsServiceRequest request,
            StreamObserver<ExportMetricsServiceResponse> responseObserver
    ) {
        responseObserver.onNext(ExportMetricsServiceResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void export(
            ExportLogsServiceRequest request,
            StreamObserver<ExportLogsServiceResponse> responseObserver
    ) {
        rawLogStore.store(request);
        responseObserver.onNext(ExportLogsServiceResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }
}
