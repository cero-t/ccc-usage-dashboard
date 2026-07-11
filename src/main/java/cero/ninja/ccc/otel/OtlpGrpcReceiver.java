package cero.ninja.ccc.otel;

import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import io.quarkus.grpc.GrpcService;

@GrpcService
public class OtlpGrpcReceiver extends TraceServiceGrpc.TraceServiceImplBase {

    @Override
    public void export(
            ExportTraceServiceRequest request,
            StreamObserver<ExportTraceServiceResponse> responseObserver
    ) {
        responseObserver.onNext(ExportTraceServiceResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }
}
