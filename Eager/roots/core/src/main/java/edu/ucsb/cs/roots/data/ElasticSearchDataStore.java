package edu.ucsb.cs.roots.data;

import com.google.common.collect.*;
import edu.ucsb.cs.roots.data.es2.*;

import java.io.IOException;
import java.util.Calendar;
import java.util.TimeZone;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public class ElasticSearchDataStore implements DataStore {

    private final ElasticSearchConfig es;

    public ElasticSearchDataStore(ElasticSearchConfig.Builder builder) {
        checkNotNull(builder, "ElasticSearchConfig builder is required");
        this.es = builder.build();
    }

    @Override
    public void destroy() {
        es.cleanup();
    }

    @Override
    public ImmutableMap<String,ResponseTimeSummary> getResponseTimeSummary(
            String application, long start, long end) throws DataStoreException {
        ResponseTimeSummaryQuery query = ResponseTimeSummaryQuery.newBuilder()
                .setStart(start)
                .setEnd(end)
                .setApplication(application)
                .build();
        try {
            return query.run(es);
        } catch (IOException e) {
            throw new DataStoreException("Error while querying ElasticSearch", e);
        }
    }

    @Override
    public ImmutableListMultimap<String,ResponseTimeSummary> getResponseTimeHistory(
            String application, long start, long end, long period) throws DataStoreException {
        ResponseTimeHistoryQuery query = ResponseTimeHistoryQuery.newBuilder()
                .setStart(start)
                .setEnd(end)
                .setPeriod(period)
                .setApplication(application)
                .build();
        try {
            return query.run(es);
        } catch (IOException e) {
            throw new DataStoreException("Error while querying ElasticSearch", e);
        }
    }

    @Override
    public ImmutableListMultimap<String, BenchmarkResult> getBenchmarkResults(
            String application, long start, long end) throws DataStoreException {
        BenchmarkResultsQuery query = BenchmarkResultsQuery.newBuilder()
                .setStart(start)
                .setEnd(end)
                .setApplication(application)
                .build();
        try {
            return query.run(es);
        } catch (IOException e) {
            throw new DataStoreException("Error while querying ElasticSearch", e);
        }
    }

    @Override
    public ImmutableList<Double> getWorkloadSummary(
            String application, String operation, long start,
            long end, long period) throws DataStoreException {
        int separator = operation.indexOf(' ');
        checkArgument(separator != -1, "Invalid operation string: %s", operation);
        WorkloadSummaryQuery query = WorkloadSummaryQuery.newBuilder()
                .setStart(start)
                .setEnd(end)
                .setPeriod(period)
                .setMethod(operation.substring(0, separator))
                .setPath(operation.substring(separator + 1))
                .setApplication(application)
                .build();
        try {
            return query.run(es);
        } catch (IOException e) {
            throw new DataStoreException("Error while querying ElasticSearch", e);
        }
    }

    @Override
    public ImmutableListMultimap<String, ApplicationRequest> getRequestInfo(
            String application, long start, long end) throws DataStoreException {
        RequestInfoQuery query = RequestInfoQuery.newBuilder()
                .setStart(start)
                .setEnd(end)
                .setApplication(application)
                .build();
        try {
            return query.run(es);
        } catch (IOException e) {
            throw new DataStoreException("Error while querying ElasticSearch", e);
        }
    }

    @Override
    public ImmutableList<ApplicationRequest> getRequestInfo(
            String application, String operation, long start, long end) throws DataStoreException {
        RequestInfoByOperationQuery query = RequestInfoByOperationQuery.newBuilder()
                .setStart(start)
                .setEnd(end)
                .setApplication(application)
                .setOperation(operation)
                .build();
        try {
            return query.run(es);
        } catch (IOException e) {
            throw new DataStoreException("Error while querying ElasticSearch", e);
        }
    }

    @Override
    public void recordBenchmarkResult(BenchmarkResult result) throws DataStoreException {
        RecordBenchmarkResultQuery query = new RecordBenchmarkResultQuery(result);
        try {
            query.run(es);
        } catch (IOException e) {
            throw new DataStoreException("Error while querying ElasticSearch", e);
        }
    }

    public static void main(String[] args) throws Exception {
        ElasticSearchConfig.Builder builder = ElasticSearchConfig.newBuilder()
                .setHost("128.111.179.246")
                .setPort(9200)
                .setBenchmarkIndex("appscale-benchmarks")
                .setApiCallIndex("appscale-apicalls")
                .setAccessLogIndex("appscale-logs");
        ElasticSearchDataStore dataStore = new ElasticSearchDataStore(builder);
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("PST"));
        cal.set(2016, Calendar.APRIL, 13, 22, 0, 0);
        long start = cal.getTime().getTime();
        cal.set(2016, Calendar.APRIL, 13, 23, 0, 0);
        long end = cal.getTime().getTime();
        Object summary = dataStore.getBenchmarkResults("javabook", start, end);
        dataStore.destroy();
    }
}
