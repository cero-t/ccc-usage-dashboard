package cero.ninja.ccc.http;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import cero.ninja.ccc.otel.RawLogStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.zip.GZIPInputStream;

@Path("/v1")
public class OtlpHttpResource {

    @Inject
    RawLogStore rawLogStore;

    /** OTLP/HTTP clients (incl. otelcol's otlphttp) default to gzip; decompress it. */
    private static byte[] decode(byte[] body, String contentEncoding) throws IOException {
        if (contentEncoding == null || !contentEncoding.toLowerCase().contains("gzip")) {
            return body;
        }
        try (var in = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return in.readAllBytes();
        }
    }

    @POST
    @Path("/traces")
    @Consumes("application/x-protobuf")
    @Produces("application/x-protobuf")
    public Response acceptTraces(@HeaderParam(HttpHeaders.CONTENT_ENCODING) String contentEncoding, byte[] body) throws Exception {
        ExportTraceServiceRequest.parseFrom(decode(body, contentEncoding));
        return Response.ok(ExportTraceServiceResponse.getDefaultInstance().toByteArray())
                .type("application/x-protobuf")
                .build();
    }

    @POST
    @Path("/metrics")
    @Consumes("application/x-protobuf")
    @Produces("application/x-protobuf")
    public Response acceptMetrics(@HeaderParam(HttpHeaders.CONTENT_ENCODING) String contentEncoding, byte[] body) throws Exception {
        ExportMetricsServiceRequest.parseFrom(decode(body, contentEncoding));
        return Response.ok(ExportMetricsServiceResponse.getDefaultInstance().toByteArray())
                .type("application/x-protobuf")
                .build();
    }

    @POST
    @Path("/logs")
    @Consumes("application/x-protobuf")
    @Produces("application/x-protobuf")
    public Response acceptLogs(@HeaderParam(HttpHeaders.CONTENT_ENCODING) String contentEncoding, byte[] body) throws Exception {
        ExportLogsServiceRequest request = ExportLogsServiceRequest.parseFrom(decode(body, contentEncoding));
        rawLogStore.store(request);
        return Response.ok(ExportLogsServiceResponse.getDefaultInstance().toByteArray())
                .type("application/x-protobuf")
                .build();
    }

    @POST
    @Path("/traces")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response acceptTracesJson(String body) {
        return unsupportedJson();
    }

    @POST
    @Path("/metrics")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response acceptMetricsJson(String body) {
        return unsupportedJson();
    }

    @POST
    @Path("/logs")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response acceptLogsJson(String body) {
        return unsupportedJson();
    }

    private Response unsupportedJson() {
        return Response.status(Response.Status.UNSUPPORTED_MEDIA_TYPE)
                .entity(Map.of(
                        "error", "OTLP/HTTP JSON is not supported",
                        "supportedContentType", "application/x-protobuf"))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
