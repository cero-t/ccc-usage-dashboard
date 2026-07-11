package cero.ninja.ccc.otel;

import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import io.quarkus.grpc.GrpcService;
import jakarta.inject.Inject;

@GrpcService
public class OtlpLogsGrpcReceiver extends LogsServiceGrpc.LogsServiceImplBase {

    @Inject
    RawLogStore rawLogStore;

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
