package cero.ninja.agent.codexusage.otel;

import cero.ninja.agent.codexusage.db.JdbcClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.ArrayValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.common.v1.KeyValueList;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Receive-path sink. Stores every OTLP log record verbatim as JSON, with no
 * filtering and no interpretation beyond the mechanical protobuf -> JSON
 * conversion. All parsing, filtering and enrichment is deferred to the annotate
 * job, so a parse bug never costs us raw data and can be re-run by rewinding the
 * cursor.
 */
@ApplicationScoped
public class RawLogStore {

    private static final String INSERT_SQL =
            "INSERT INTO otel_log_records (record_json) VALUES (:record_json)";

    @Inject
    JdbcClient db;

    @Inject
    ObjectMapper objectMapper;

    public void store(ExportLogsServiceRequest request) {
        for (ResourceLogs resourceLogs : request.getResourceLogsList()) {
            Map<String, Object> resourceAttributes = attributes(resourceLogs.getResource().getAttributesList());
            for (ScopeLogs scopeLogs : resourceLogs.getScopeLogsList()) {
                String scopeName = scopeLogs.getScope().getName();
                for (LogRecord record : scopeLogs.getLogRecordsList()) {
                    db.sql(INSERT_SQL)
                            .param("record_json", json(toEnvelope(scopeName, resourceAttributes, record)))
                            .update();
                }
            }
        }
    }

    private Map<String, Object> toEnvelope(
            String scopeName,
            Map<String, Object> resourceAttributes,
            LogRecord record
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope_name", scopeName);
        out.put("severity_text", record.getSeverityText());
        out.put("observed_time_unix_nano", record.getObservedTimeUnixNano());
        out.put("time_unix_nano", record.getTimeUnixNano());
        out.put("trace_id", bytesToHex(record.getTraceId().toByteArray()));
        out.put("span_id", bytesToHex(record.getSpanId().toByteArray()));
        out.put("body", anyValueToJava(record.getBody()));
        out.put("resource_attributes", resourceAttributes);
        out.put("attributes", attributes(record.getAttributesList()));
        return out;
    }

    private Map<String, Object> attributes(List<KeyValue> attributes) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (KeyValue entry : attributes) {
            out.put(entry.getKey(), anyValueToJava(entry.getValue()));
        }
        return out;
    }

    private Object anyValueToJava(AnyValue value) {
        return switch (value.getValueCase()) {
            case STRING_VALUE -> value.getStringValue();
            case BOOL_VALUE -> value.getBoolValue();
            case INT_VALUE -> value.getIntValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case BYTES_VALUE -> Base64.getEncoder().encodeToString(value.getBytesValue().toByteArray());
            case ARRAY_VALUE -> arrayToJava(value.getArrayValue());
            case KVLIST_VALUE -> kvListToJava(value.getKvlistValue());
            case VALUE_NOT_SET -> null;
        };
    }

    private List<Object> arrayToJava(ArrayValue array) {
        List<Object> out = new ArrayList<>(array.getValuesCount());
        for (AnyValue value : array.getValuesList()) {
            out.add(anyValueToJava(value));
        }
        return out;
    }

    private Map<String, Object> kvListToJava(KeyValueList kvList) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (KeyValue entry : kvList.getValuesList()) {
            out.put(entry.getKey(), anyValueToJava(entry.getValue()));
        }
        return out;
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize OTLP log record", e);
        }
    }

    private String bytesToHex(byte[] value) {
        if (value.length == 0) {
            return null;
        }
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) {
            out.append(Character.forDigit((b >>> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
