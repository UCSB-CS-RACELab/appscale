package edu.ucsb.cs.roots.scheduling;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import edu.ucsb.cs.roots.ManagedService;
import edu.ucsb.cs.roots.RootsEnvironment;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

public abstract class SchedulerService<T extends ScheduledItem> extends ManagedService {

    private final String instanceName;
    private final String threadPool;
    private final int threadCount;
    private final String group;

    private Scheduler scheduler;
    private final List<T> items = new ArrayList<>();

    public SchedulerService(RootsEnvironment environment, String instanceName,
                            String threadPool, int threadCount, String group) {
        super(environment);
        checkArgument(!Strings.isNullOrEmpty(instanceName));
        checkArgument(!Strings.isNullOrEmpty(threadPool));
        checkArgument(threadCount > 0);
        checkArgument(!Strings.isNullOrEmpty(group));
        this.instanceName = instanceName;
        this.threadPool = threadPool;
        this.threadCount = threadCount;
        this.group = group;
    }

    @Override
    protected void doInit() throws Exception {
        scheduler = initScheduler();
        scheduler.start();
        loadItems().forEach(p -> {
            try {
                scheduleItem(p);
            } catch (Exception e) {
                log.warn("Error while scheduling detector", e);
            }
        });
    }

    @Override
    protected void doDestroy() {
        ImmutableList.copyOf(items).forEach(this::cancelItem);
        items.clear();
        try {
            scheduler.shutdown(true);
        } catch (SchedulerException e) {
            log.warn("Error while stopping the scheduler");
        }
    }

    protected abstract T createItem(RootsEnvironment environment, Properties properties);

    protected Stream<Properties> loadItems() {
        return Stream.empty();
    }

    private JobKey getJobKey(String application) {
        return JobKey.jobKey(application + "-job", group);
    }

    private TriggerKey getTriggerKey(String application) {
        return TriggerKey.triggerKey(application + "-job", group);
    }

    private Scheduler initScheduler() throws SchedulerException {
        StdSchedulerFactory factory = new StdSchedulerFactory();
        Properties properties = new Properties();
        properties.setProperty(StdSchedulerFactory.PROP_SCHED_INSTANCE_NAME, instanceName);
        properties.setProperty(StdSchedulerFactory.PROP_THREAD_POOL_CLASS, threadPool);
        properties.setProperty("org.quartz.threadPool.threadCount", String.valueOf(threadCount));
        factory.initialize(properties);
        checkState(factory.getScheduler(instanceName) == null,
                "Attempting to reuse existing Scheduler");
        return factory.getScheduler();
    }

    private void cancelItem(T item) {
        try {
            scheduler.unscheduleJob(getTriggerKey(item.getApplication()));
            items.remove(item);
            log.info("Cancelled detector job for: {}", item.getApplication());
        } catch (SchedulerException e) {
            log.warn("Error while cancelling the detector for: {}", item.getApplication(), e);
        }
    }

    private void scheduleItem(Properties properties) throws SchedulerException {
        T detector = createItem(environment, properties);
        JobDetail jobDetail = JobBuilder.newJob(RootsJob.class)
                .withIdentity(getJobKey(detector.getApplication()))
                .build();
        JobDataMap jobDataMap = jobDetail.getJobDataMap();
        jobDataMap.put(RootsJob.SCHEDULED_ITEM_INSTANCE, detector);

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(getTriggerKey(detector.getApplication()))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withMisfireHandlingInstructionIgnoreMisfires()
                        .repeatForever().withIntervalInSeconds(detector.getPeriodInSeconds()))
                .startNow()
                .build();
        scheduler.scheduleJob(jobDetail, trigger);
        items.add(detector);
        log.info("Scheduled job for: {}", detector.getApplication());
    }
}
