package edu.ucsb.cs.roots.bi;

import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.Subscribe;
import edu.ucsb.cs.roots.ManagedService;
import edu.ucsb.cs.roots.RootsEnvironment;
import edu.ucsb.cs.roots.anomaly.Anomaly;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BottleneckFinderService extends ManagedService {

    private static final Logger log = LoggerFactory.getLogger(BottleneckFinderService.class);

    public static final String BI_FINDERS = "bi.finders";
    public static final String BI_PELT_PENALTY = "bi.pelt.penalty";
    public static final String BI_PERCENTILE = "bi.percentile";

    public BottleneckFinderService(RootsEnvironment environment) {
        super(environment);
    }

    @Override
    protected void doInit() throws Exception {
        environment.subscribe(this);
    }

    @Override
    protected void doDestroy() {
    }

    @Subscribe
    public void run(Anomaly anomaly) {
        if (anomaly.getType() == Anomaly.TYPE_WORKLOAD) {
            return;
        }

        String findersProperty = anomaly.getDetectorProperty(BI_FINDERS, null);
        if (findersProperty == null) {
            findersProperty = environment.getProperty(BI_FINDERS, "RelativeImportance");
        }
        String[] finderTypes = findersProperty.split(",");
        ImmutableList.Builder<BottleneckFinder> findersBuilder = ImmutableList.builder();
        for (String finderType : finderTypes) {
            findersBuilder.add(newFinder(anomaly, finderType.trim()));
        }

        for (BottleneckFinder finder : findersBuilder.build()) {
            try {
                finder.analyze(anomaly);
            } catch (Exception e) {
                log.error("Error during bottleneck identification", e);
            }
        }
    }

    private BottleneckFinder newFinder(Anomaly anomaly, String type) {
        if ("RelativeImportance".equals(type)) {
            String peltPenalty = anomaly.getDetectorProperty(BI_PELT_PENALTY, null);
            if (peltPenalty == null) {
                peltPenalty = environment.getProperty(BI_PELT_PENALTY, "0.1");
            }
            return new RelativeImportanceBasedFinder(environment, Double.parseDouble(peltPenalty));
        } else if ("Percentile".equals(type)) {
            String percentile = anomaly.getDetectorProperty(BI_PERCENTILE, null);
            if (percentile == null) {
                percentile = environment.getProperty(BI_PERCENTILE, "95.0");
            }
            return new PercentileBasedFinder(environment, Double.parseDouble(percentile));
        } else {
            throw new IllegalArgumentException("Unknown bottleneck finder: " + type);
        }
    }
}
