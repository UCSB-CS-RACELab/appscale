package edu.ucsb.cs.roots.data;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import edu.ucsb.cs.roots.RootsEnvironment;

import java.util.ArrayList;
import java.util.List;

public class TestDataStore extends DataStore {

    public static final String GET_BENCHMARK_RESULTS = "GET_BENCHMARK_RESULTS";

    private final List<DataStoreCall> calls = new ArrayList<>();

    public TestDataStore(RootsEnvironment environment, String name) {
        environment.getDataStoreService().put(name, this);
    }

    @Override
    public ImmutableMap<String, ResponseTimeSummary> getResponseTimeSummary(
            String application, long start, long end) throws DataStoreException {
        return null;
    }

    @Override
    public ImmutableMap<String, ImmutableList<ResponseTimeSummary>> getResponseTimeHistory(
            String application, long start, long end, long period) throws DataStoreException {
        return null;
    }

    @Override
    public ImmutableMap<String, ImmutableList<AccessLogEntry>> getBenchmarkResults(
            String application, long start, long end) throws DataStoreException {
        calls.add(new DataStoreCall(start, end, GET_BENCHMARK_RESULTS, application));
        return ImmutableMap.of();
    }

    public int callCount() {
        return calls.size();
    }

    public List<DataStoreCall> getCallsAndClear() {
        ImmutableList copy = ImmutableList.copyOf(calls);
        calls.clear();
        return copy;
    }

}
