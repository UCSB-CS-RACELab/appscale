package edu.ucsb.cs.roots.anomaly;

import com.google.common.base.Strings;
import edu.ucsb.cs.roots.RootsEnvironment;
import edu.ucsb.cs.roots.scheduling.ScheduledItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public abstract class AnomalyDetector extends ScheduledItem {

    protected final RootsEnvironment environment;
    protected final int historyLengthInSeconds;
    protected final String dataStore;
    protected final Properties properties;
    protected final Logger log = LoggerFactory.getLogger(getClass());

    public AnomalyDetector(RootsEnvironment environment, AnomalyDetectorBuilder builder) {
        super(builder.application, builder.periodInSeconds);
        checkNotNull(environment, "Environment must not be null");
        checkArgument(builder.historyLengthInSeconds > 0, "Period must be a positive integer");
        checkArgument(builder.historyLengthInSeconds % builder.periodInSeconds == 0,
                "History length must be a multiple of period");
        checkArgument(!Strings.isNullOrEmpty(builder.dataStore), "DataStore name is required");
        checkNotNull(builder.properties, "Properties must not be null");
        this.environment = environment;
        this.historyLengthInSeconds =builder.historyLengthInSeconds;
        this.dataStore = builder.dataStore;
        this.properties = builder.properties;
    }

    public final String getDataStore() {
        return dataStore;
    }

    public final String getProperty(String key, String def) {
        return properties.getProperty(key, def);
    }

    protected final void reportAnomaly(long start, long end, int type,
                                       String operation, String description) {
        environment.publishEvent(new Anomaly(this, start, end, type, operation, description));
    }

}
