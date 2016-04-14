package edu.ucsb.cs.roots.data;

import com.google.common.base.Strings;
import com.google.common.collect.*;
import com.google.gson.*;
import edu.ucsb.cs.roots.data.es.*;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import static com.google.common.base.Preconditions.checkArgument;

public class ElasticSearchDataStore implements DataStore {

    private static final Logger log = LoggerFactory.getLogger(ElasticSearchDataStore.class);

    private static final ImmutableList<String> METHODS = ImmutableList.of(
            "GET", "POST", "PUT", "DELETE");
    private static final Gson GSON = new Gson();

    private static final String ACCESS_LOG_REQ_ID = "field.accessLog.requestId";
    private static final String ACCESS_LOG_TIMESTAMP = "field.accessLog.timestamp";
    private static final String ACCESS_LOG_METHOD = "field.accessLog.method";
    private static final String ACCESS_LOG_PATH = "field.accessLog.path";
    private static final String ACCESS_LOG_RESPONSE_TIME = "field.accessLog.responseTime";

    private static final String BENCHMARK_TIMESTAMP = "field.benchmark.timestamp";
    private static final String BENCHMARK_METHOD = "field.benchmark.method";
    private static final String BENCHMARK_PATH = "field.benchmark.path";
    private static final String BENCHMARK_RESPONSE_TIME = "field.benchmark.responseTime";

    private static final String API_CALL_REQ_TIMESTAMP = "field.apiCall.requestTimestamp";
    private static final String API_CALL_REQ_OPERATION = "field.apiCall.requestOperation";
    private static final String API_CALL_TIMESTAMP = "field.apiCall.timestamp";
    private static final String API_CALL_SEQ_NUMBER = "field.apiCall.sequenceNumber";
    private static final String API_CALL_APPLICATION = "field.apiCall.application";
    private static final String API_CALL_SERVICE = "field.apiCall.service";
    private static final String API_CALL_OPERATION = "field.apiCall.operation";
    private static final String API_CALL_RESPONSE_TIME = "field.apiCall.responseTime";
    private static final String API_CALL_REQ_ID = "field.apiCall.requestId";

    private static final ImmutableMap<String, String> DEFAULT_FIELD_MAPPINGS =
            ImmutableMap.<String, String>builder()
                    .put(ACCESS_LOG_TIMESTAMP, "@timestamp")
                    .put(ACCESS_LOG_REQ_ID, "request_id")
                    .put(ACCESS_LOG_METHOD, "http_verb")
                    .put(ACCESS_LOG_PATH, "http_request")
                    .put(ACCESS_LOG_RESPONSE_TIME, "time_duration")
                    .put(BENCHMARK_TIMESTAMP, "timestamp")
                    .put(BENCHMARK_METHOD, "method")
                    .put(BENCHMARK_PATH, "path")
                    .put(BENCHMARK_RESPONSE_TIME, "responseTime")
                    .put(API_CALL_TIMESTAMP, "timestamp")
                    .put(API_CALL_REQ_TIMESTAMP, "requestTimestamp")
                    .put(API_CALL_REQ_OPERATION, "requestOperation")
                    .put(API_CALL_SEQ_NUMBER, "sequenceNumber")
                    .put(API_CALL_APPLICATION, "appId")
                    .put(API_CALL_SERVICE, "service")
                    .put(API_CALL_OPERATION, "operation")
                    .put(API_CALL_RESPONSE_TIME, "elapsed")
                    .put(API_CALL_REQ_ID, "requestId")
                    .build();

    private final String elasticSearchHost;
    private final int elasticSearchPort;

    private final String accessLogIndex;
    private final String benchmarkIndex;
    private final String apiCallIndex;

    private final ImmutableMap<String,String> fieldMappings;
    private final boolean rawStringFilter;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private final CloseableHttpClient httpClient;

    private ElasticSearchDataStore(Builder builder) {
        checkArgument(!Strings.isNullOrEmpty(builder.elasticSearchHost),
                "ElasticSearch host is required");
        checkArgument(builder.elasticSearchPort > 0 && builder.elasticSearchPort < 65535,
                "ElasticSearch port number is invalid");
        checkArgument(builder.connectTimeout >= -1);
        checkArgument(builder.socketTimeout >= -1);
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(builder.connectTimeout)
                .setSocketTimeout(builder.socketTimeout)
                .build();
        this.httpClient = HttpClients.custom().setDefaultRequestConfig(requestConfig).build();
        this.elasticSearchHost = builder.elasticSearchHost;
        this.elasticSearchPort = builder.elasticSearchPort;
        this.accessLogIndex = builder.accessLogIndex;
        this.benchmarkIndex = builder.benchmarkIndex;
        this.apiCallIndex = builder.apiCallIndex;
        this.fieldMappings = ImmutableMap.copyOf(builder.fieldMappings);
        this.rawStringFilter = builder.rawStringFilter;
    }

    @Override
    public void destroy() {
        IOUtils.closeQuietly(httpClient);
    }

    @Override
    public ImmutableMap<String,ResponseTimeSummary> getResponseTimeSummary(
            String application, long start, long end) throws DataStoreException {
        checkArgument(!Strings.isNullOrEmpty(accessLogIndex), "Access log index is required");
        String query = ResponseTimeSummaryQuery.newBuilder()
                .setAccessLogTimestampField(fieldMappings.get(ACCESS_LOG_TIMESTAMP))
                .setAccessLogMethodField(fieldMappings.get(ACCESS_LOG_METHOD))
                .setAccessLogPathField(fieldMappings.get(ACCESS_LOG_PATH))
                .setAccessLogResponseTimeField(fieldMappings.get(ACCESS_LOG_RESPONSE_TIME))
                .setStart(start)
                .setEnd(end)
                .setRawStringFilter(rawStringFilter)
                .buildJsonString();
        String path = String.format("/%s/%s/_search", accessLogIndex, application);
        ImmutableMap.Builder<String,ResponseTimeSummary> builder = ImmutableMap.builder();
        try {
            JsonElement results = makeHttpCall(path, query);
            parseResponseTimeSummary(results, start, builder);
        } catch (IOException | URISyntaxException e) {
            throw new DataStoreException("Error while querying ElasticSearch", e);
        }
        return builder.build();
    }

    @Override
    public ImmutableListMultimap<String,ResponseTimeSummary> getResponseTimeHistory(
            String application, long start, long end, long period) throws DataStoreException {
        checkArgument(!Strings.isNullOrEmpty(accessLogIndex), "Access log index is required");
        String query = ResponseTimeHistoryQuery.newBuilder()
                .setAccessLogTimestampField(fieldMappings.get(ACCESS_LOG_TIMESTAMP))
                .setAccessLogMethodField(fieldMappings.get(ACCESS_LOG_METHOD))
                .setAccessLogPathField(fieldMappings.get(ACCESS_LOG_PATH))
                .setAccessLogResponseTimeField(fieldMappings.get(ACCESS_LOG_RESPONSE_TIME))
                .setStart(start)
                .setEnd(end)
                .setPeriod(period)
                .setRawStringFilter(rawStringFilter)
                .buildJsonString();
        String path = String.format("/%s/%s/_search", accessLogIndex, application);
        ImmutableListMultimap.Builder<String,ResponseTimeSummary> builder = ImmutableListMultimap.builder();
        try {
            JsonElement results = makeHttpCall(path, query);
            parseResponseTimeHistory(results, builder);
        } catch (IOException | URISyntaxException e) {
            throw new DataStoreException("Error while querying ElasticSearch", e);
        }
        return builder.build();
    }

    @Override
    public ImmutableListMultimap<String, BenchmarkResult> getBenchmarkResults(
            String application, long start, long end) throws DataStoreException {
        checkArgument(!Strings.isNullOrEmpty(benchmarkIndex), "Benchmark index is required");
        String query = BenchmarkResultsQuery.newBuilder()
                .setBenchmarkTimestampField(fieldMappings.get(BENCHMARK_TIMESTAMP))
                .setStart(start)
                .setEnd(end)
                .buildJsonString();
        String path = String.format("/%s/%s/_search", benchmarkIndex, application);
        ImmutableListMultimap.Builder<String,BenchmarkResult> builder = ImmutableListMultimap.builder();
        try {
            JsonElement results = makeHttpCall(path, "scroll=1m", query);
            String scrollId = results.getAsJsonObject().get("_scroll_id").getAsString();
            long total = results.getAsJsonObject().getAsJsonObject("hits").get("total").getAsLong();
            long received = 0L;
            while (true) {
                received += parseBenchmarkResults(application, results, builder);
                if (received >= total) {
                    break;
                }
                results = makeHttpCall("/_search/scroll", ScrollQuery.build(scrollId));
            }
        } catch (IOException | URISyntaxException e) {
            throw new DataStoreException("Error while querying ElasticSearch", e);
        }
        return builder.build();
    }

    @Override
    public ImmutableList<Double> getWorkloadSummary(
            String application, String operation, long start,
            long end, long period) throws DataStoreException {
        checkArgument(!Strings.isNullOrEmpty(accessLogIndex), "Access log index is required");
        int separator = operation.indexOf(' ');
        checkArgument(separator != -1, "Invalid operation string: %s", operation);
        String query = WorkloadSummaryQuery.newBuilder()
                .setStart(start)
                .setEnd(end)
                .setPeriod(period)
                .setAccessLogMethodField(fieldMappings.get(ACCESS_LOG_METHOD))
                .setAccessLogPathField(fieldMappings.get(ACCESS_LOG_PATH))
                .setAccessLogTimestampField(fieldMappings.get(ACCESS_LOG_TIMESTAMP))
                .setMethod(operation.substring(0, separator))
                .setPath(operation.substring(separator + 1))
                .setRawStringFilter(rawStringFilter)
                .buildJsonString();
        String path = String.format("/%s/%s/_search", accessLogIndex, application);
        ImmutableList.Builder<Double> builder = ImmutableList.builder();
        try {
            JsonElement results = makeHttpCall(path, query);
            JsonArray periods = results.getAsJsonObject().getAsJsonObject("aggregations")
                    .getAsJsonObject("periods").getAsJsonArray("buckets");
            periods.forEach(p -> builder.add(
                    p.getAsJsonObject().get("doc_count").getAsDouble()));
        } catch (IOException | URISyntaxException e) {
            throw new DataStoreException("Error while querying ElasticSearch", e);
        }
        return builder.build();
    }

    @Override
    public ImmutableListMultimap<String, ApplicationRequest> getRequestInfo(
            String application, long start, long end) throws DataStoreException {
        checkArgument(!Strings.isNullOrEmpty(apiCallIndex), "API Call index is required");
        String query = RequestInfoQuery.newBuilder()
                .setStart(start)
                .setEnd(end)
                .setApiCallSequenceNumberField(fieldMappings.get(API_CALL_SEQ_NUMBER))
                .setApiCallRequestTimestampField(fieldMappings.get(API_CALL_REQ_TIMESTAMP))
                .buildJsonString();
        String path = String.format("/%s/%s/_search", apiCallIndex, application);
        ImmutableListMultimap<String,ApiCall> apiCalls = getRequestInfo(path, query);

        ImmutableSetMultimap.Builder<String,ApplicationRequest> builder = ImmutableSetMultimap
                .<String,ApplicationRequest>builder().orderValuesBy(ApplicationRequest.TIME_ORDER);
        apiCalls.keySet().forEach(requestId -> {
            ImmutableList<ApiCall> calls = apiCalls.get(requestId);
            ApiCall firstCall = calls.get(0);
            ApplicationRequest req = new ApplicationRequest(requestId, firstCall.getRequestTimestamp(),
                    application, firstCall.getRequestOperation(), calls);
            builder.put(firstCall.getRequestOperation(), req);
        });
        return ImmutableListMultimap.copyOf(builder.build());
    }

    @Override
    public ImmutableList<ApplicationRequest> getRequestInfo(
            String application, String operation, long start, long end) throws DataStoreException {
        checkArgument(!Strings.isNullOrEmpty(apiCallIndex), "API Call index is required");
        String query = RequestInfoByOperationQuery.newBuilder()
                .setStart(start)
                .setEnd(end)
                .setRequestOperation(operation)
                .setRequestOperationField(fieldMappings.get(API_CALL_REQ_OPERATION))
                .setApiCallSequenceNumberField(fieldMappings.get(API_CALL_SEQ_NUMBER))
                .setApiCallRequestTimestampField(fieldMappings.get(API_CALL_REQ_TIMESTAMP))
                .setRawStringFilter(rawStringFilter)
                .buildJsonString();
        String path = String.format("/%s/%s/_search", apiCallIndex, application);
        ImmutableListMultimap<String,ApiCall> apiCalls = getRequestInfo(path, query);

        query = BenchmarkResultsQuery.newBuilder()
                .setBenchmarkTimestampField(fieldMappings.get(ACCESS_LOG_TIMESTAMP))
                .setStart(start)
                .setEnd(end)
                .buildJsonString();
        path = String.format("/%s/%s/_search", accessLogIndex, application);
        ImmutableMap<String,AccessLogEntry> accessLogEntries = getAccessLogEntries(
                application, path, query);

        ImmutableSortedSet.Builder<ApplicationRequest> builder = ImmutableSortedSet.orderedBy(
                ApplicationRequest.TIME_ORDER);
        apiCalls.keySet().stream().filter(accessLogEntries::containsKey).forEach(requestId -> {
            ImmutableList<ApiCall> calls = apiCalls.get(requestId);
            ApplicationRequest req = new ApplicationRequest(requestId, calls.get(0).getRequestTimestamp(),
                    application, operation, calls, accessLogEntries.get(requestId).getResponseTime());
            builder.add(req);
        });
        return ImmutableList.copyOf(builder.build());
    }

    @Override
    public void recordBenchmarkResult(BenchmarkResult result) throws DataStoreException {
        checkArgument(!Strings.isNullOrEmpty(benchmarkIndex), "Benchmark index is required");
        String path = String.format("/%s/%s", benchmarkIndex, result.getApplication());
        try {
            makeHttpCall(path, GSON.toJson(result));
        } catch (IOException | URISyntaxException e) {
            throw new DataStoreException("Error while querying ElasticSearch", e);
        }
    }

    private void parseResponseTimeSummary(JsonElement element, long timestamp,
                                          ImmutableMap.Builder<String,ResponseTimeSummary> builder) {
        JsonArray methods = element.getAsJsonObject().getAsJsonObject("aggregations")
                .getAsJsonObject("methods").getAsJsonArray("buckets");
        for (int i = 0; i < methods.size(); i++) {
            JsonObject method = methods.get(i).getAsJsonObject();
            String methodName = method.get("key").getAsString().toUpperCase();
            if (!METHODS.contains(methodName)) {
                continue;
            }
            JsonArray paths = method.getAsJsonObject("paths").getAsJsonArray("buckets");
            for (JsonElement pathElement : paths) {
                JsonObject path = pathElement.getAsJsonObject();
                String key = methodName + " " + path.get("key").getAsString();
                builder.put(key, newResponseTimeSummary(timestamp, path));
            }
        }
    }

    private void parseResponseTimeHistory(JsonElement element,
                                          ImmutableListMultimap.Builder<String,ResponseTimeSummary> builder) {
        JsonArray methods = element.getAsJsonObject().getAsJsonObject("aggregations")
                .getAsJsonObject("methods").getAsJsonArray("buckets");
        for (JsonElement methodElement : methods) {
            JsonObject method = methodElement.getAsJsonObject();
            String methodName = method.get("key").getAsString().toUpperCase();
            if (!METHODS.contains(methodName)) {
                continue;
            }
            JsonArray paths = method.getAsJsonObject("paths").getAsJsonArray("buckets");
            for (JsonElement pathElement : paths) {
                JsonObject path = pathElement.getAsJsonObject();
                String key = methodName + " " + path.get("key").getAsString();
                JsonArray periods = path.getAsJsonObject("periods").getAsJsonArray("buckets");
                for (JsonElement periodElement : periods) {
                    JsonObject period = periodElement.getAsJsonObject();
                    double count = period.get("doc_count").getAsDouble();
                    if (count > 0) {
                        long timestamp = period.get("key").getAsLong();
                        builder.put(key, newResponseTimeSummary(timestamp, period));
                    }
                }
            }
        }
    }

    private int parseBenchmarkResults(String application, JsonElement element,
                                       ImmutableListMultimap.Builder<String,BenchmarkResult> builder) {
        JsonArray hits = element.getAsJsonObject().getAsJsonObject("hits")
                .getAsJsonArray("hits");
        for (JsonElement hitElement : hits) {
            JsonObject hit = hitElement.getAsJsonObject().getAsJsonObject("_source");
            BenchmarkResult result = new BenchmarkResult(
                    hit.get(fieldMappings.get(BENCHMARK_TIMESTAMP)).getAsLong(),
                    application,
                    hit.get(fieldMappings.get(BENCHMARK_METHOD)).getAsString(),
                    hit.get(fieldMappings.get(BENCHMARK_PATH)).getAsString(),
                    hit.get(fieldMappings.get(BENCHMARK_RESPONSE_TIME)).getAsInt());
            builder.put(result.getRequestType(), result);
        }
        return hits.size();
    }

    private ImmutableListMultimap<String,ApiCall> getRequestInfo(
            String path, String query) throws DataStoreException {
        ImmutableListMultimap.Builder<String,ApiCall> builder = ImmutableListMultimap.builder();
        try {
            JsonElement results = makeHttpCall(path, "scroll=1m", query);
            String scrollId = results.getAsJsonObject().get("_scroll_id").getAsString();
            long total = results.getAsJsonObject().getAsJsonObject("hits").get("total").getAsLong();
            long received = 0L;
            while (true) {
                received += parseApiCalls(results, builder);
                if (received >= total) {
                    break;
                }
                results = makeHttpCall("/_search/scroll", ScrollQuery.build(scrollId));
            }
            return builder.build();
        } catch (IOException | URISyntaxException e) {
            throw new DataStoreException("Error while querying ElasticSearch", e);
        }
    }

    private ImmutableMap<String,AccessLogEntry> getAccessLogEntries(
            String application, String path, String query) throws DataStoreException {

        ImmutableMap.Builder<String,AccessLogEntry> builder = ImmutableMap.builder();
        try {
            JsonElement results = makeHttpCall(path, "scroll=1m", query);
            String scrollId = results.getAsJsonObject().get("_scroll_id").getAsString();
            long total = results.getAsJsonObject().getAsJsonObject("hits").get("total").getAsLong();
            long received = 0L;
            while (true) {
                received += parseAccessLogEntries(application, results, builder);
                if (received >= total) {
                    break;
                }
                results = makeHttpCall("/_search/scroll", ScrollQuery.build(scrollId));
            }
            return builder.build();
        } catch (IOException | URISyntaxException e) {
            throw new DataStoreException("Error while querying ElasticSearch", e);
        }
    }

    private int parseApiCalls(JsonElement element, ImmutableListMultimap.Builder<String,ApiCall> builder) {
        JsonArray hits = element.getAsJsonObject().getAsJsonObject("hits")
                .getAsJsonArray("hits");
        for (JsonElement hitElement : hits) {
            JsonObject hit = hitElement.getAsJsonObject().getAsJsonObject("_source");
            ApiCall call = ApiCall.newBuilder()
                    .setRequestTimestamp(hit.get(fieldMappings.get(API_CALL_REQ_TIMESTAMP)).getAsLong())
                    .setRequestOperation(hit.get(fieldMappings.get(API_CALL_REQ_OPERATION)).getAsString())
                    .setTimestamp(hit.get(fieldMappings.get(API_CALL_TIMESTAMP)).getAsLong())
                    .setService(hit.get(fieldMappings.get(API_CALL_SERVICE)).getAsString())
                    .setOperation(hit.get(fieldMappings.get(API_CALL_OPERATION)).getAsString())
                    .setTimeElapsed(hit.get(fieldMappings.get(API_CALL_RESPONSE_TIME)).getAsInt())
                    .build();
            builder.put(hit.get(fieldMappings.get(API_CALL_REQ_ID)).getAsString(), call);
        }
        return hits.size();
    }

    private int parseAccessLogEntries(String application, JsonElement element,
                                      ImmutableMap.Builder<String,AccessLogEntry> builder) {
        JsonArray hits = element.getAsJsonObject().getAsJsonObject("hits")
                .getAsJsonArray("hits");
        for (JsonElement hitElement : hits) {
            JsonObject hit = hitElement.getAsJsonObject().getAsJsonObject("_source");
            String timeString = hit.get(fieldMappings.get(ACCESS_LOG_TIMESTAMP)).getAsString();
            try {
                Date timestamp = dateFormat.parse(timeString);
                AccessLogEntry entry = new AccessLogEntry(
                        hit.get(fieldMappings.get(ACCESS_LOG_REQ_ID)).getAsString(),
                        timestamp.getTime(),
                        application,
                        hit.get(fieldMappings.get(ACCESS_LOG_METHOD)).getAsString(),
                        hit.get(fieldMappings.get(ACCESS_LOG_PATH)).getAsString(),
                        (int) (hit.get(fieldMappings.get(ACCESS_LOG_RESPONSE_TIME)).getAsDouble() * 1000));
                builder.put(entry.getRequestId(), entry);
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }
        }
        return hits.size();
    }

    private ResponseTimeSummary newResponseTimeSummary(long timestamp, JsonObject bucket) {
        double responseTime = bucket.getAsJsonObject("avg_time").get("value")
                .getAsDouble() * 1000.0;
        double requestCount = bucket.get("doc_count").getAsDouble();
        return new ResponseTimeSummary(timestamp, responseTime, requestCount);
    }

    private JsonElement makeHttpCall(String path,
                                     String json) throws IOException, URISyntaxException {
        return makeHttpCall(path, null, json);
    }

    private JsonElement makeHttpCall(String path, String query,
                                     String json) throws IOException, URISyntaxException {
        URI uri = new URI("http", null, elasticSearchHost, elasticSearchPort, path, query, null);
        log.debug("URL: {}; Payload: {}", uri, json);
        HttpPost post = new HttpPost(uri);
        post.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
        return httpClient.execute(post, new ElasticSearchResponseHandler());
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    private static class ElasticSearchResponseHandler implements ResponseHandler<JsonElement> {
        @Override
        public JsonElement handleResponse(HttpResponse response) throws IOException {
            int status = response.getStatusLine().getStatusCode();
            HttpEntity entity = response.getEntity();
            if (status != 200 && status != 201) {
                String error = entity != null ? EntityUtils.toString(entity) : null;
                throw new ClientProtocolException("Unexpected status code: " + status
                        + "; response: " + error);
            }

            if (entity == null) {
                return null;
            }
            JsonParser parser = new JsonParser();
            ContentType contentType = ContentType.getOrDefault(entity);
            Charset charset = contentType.getCharset() != null ?
                    contentType.getCharset() : Charset.defaultCharset();
            return parser.parse(new InputStreamReader(entity.getContent(), charset));
        }
    }

    public static class Builder {

        private String elasticSearchHost;
        private int elasticSearchPort;
        private String accessLogIndex;
        private String benchmarkIndex;
        private String apiCallIndex;
        private int connectTimeout = -1;
        private int socketTimeout = -1;
        private boolean rawStringFilter = true;

        private final Map<String,String> fieldMappings = new HashMap<>(DEFAULT_FIELD_MAPPINGS);

        private Builder() {
        }

        public Builder setElasticSearchHost(String elasticSearchHost) {
            this.elasticSearchHost = elasticSearchHost;
            return this;
        }

        public Builder setElasticSearchPort(int elasticSearchPort) {
            this.elasticSearchPort = elasticSearchPort;
            return this;
        }

        public Builder setAccessLogIndex(String accessLogIndex) {
            this.accessLogIndex = accessLogIndex;
            return this;
        }

        public Builder setBenchmarkIndex(String benchmarkIndex) {
            this.benchmarkIndex = benchmarkIndex;
            return this;
        }

        public Builder setApiCallIndex(String apiCallIndex) {
            this.apiCallIndex = apiCallIndex;
            return this;
        }

        public Builder setConnectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder setSocketTimeout(int socketTimeout) {
            this.socketTimeout = socketTimeout;
            return this;
        }

        public Builder setFieldMapping(String field, String mapping) {
            fieldMappings.put(field, mapping);
            return this;
        }

        public Builder setRawStringFilter(boolean rawStringFilter) {
            this.rawStringFilter = rawStringFilter;
            return this;
        }

        public ElasticSearchDataStore build() {
            return new ElasticSearchDataStore(this);
        }
    }
}
