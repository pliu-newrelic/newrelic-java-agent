package com.nr.instrumentation.kafka;

import com.newrelic.agent.bridge.AgentBridge;
import com.newrelic.api.agent.NewRelic;
import org.apache.kafka.common.metrics.KafkaMetric;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static com.nr.instrumentation.kafka.MetricsConstants.KAFKA_METRICS_DEBUG;
import static com.nr.instrumentation.kafka.MetricsConstants.METRICS_AS_EVENTS;
import static com.nr.instrumentation.kafka.MetricsConstants.METRICS_EVENT_TYPE;
import static com.nr.instrumentation.kafka.MetricsConstants.METRIC_PREFIX;
import static com.nr.instrumentation.kafka.MetricsConstants.REPORTING_INTERVAL_IN_SECONDS;

public class MetricsScheduler {
    private static ScheduledThreadPoolExecutor executor = null;
    private static final Map<NewRelicMetricsReporter, ScheduledFuture<?>> metricReporterTasks = new ConcurrentHashMap<>();
    private static final Object lock = new Object();

    private MetricsScheduler() {}

    public static void addMetricsReporter(NewRelicMetricsReporter metricsReporter) {
        synchronized (lock) {
            if (executor == null) {
                executor = createScheduledExecutor();
            }
            ScheduledFuture<?> task = executor.scheduleAtFixedRate(
                    new MetricsSendRunnable(metricsReporter),
                    0L,
                    REPORTING_INTERVAL_IN_SECONDS,
                    TimeUnit.SECONDS);
            metricReporterTasks.put(metricsReporter, task);
        }
    }

    public static void removeMetricsReporter(NewRelicMetricsReporter metricsReporter) {
        synchronized (lock) {
            ScheduledFuture<?> task = metricReporterTasks.remove(metricsReporter);
            task.cancel(false);
            if (metricReporterTasks.isEmpty()) {
                executor.shutdown();
                executor = null;
            }
        }
    }

    private static ScheduledThreadPoolExecutor createScheduledExecutor() {
        return new ScheduledThreadPoolExecutor(1, buildThreadFactory("NewRelicMetricsReporter-%d"));
    }

    private static ThreadFactory buildThreadFactory(final String nameFormat) {
        // fail fast if the format is invalid
        String.format(nameFormat, 0);

        final ThreadFactory factory = Executors.defaultThreadFactory();
        final AtomicInteger count = new AtomicInteger();

        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                final Thread thread = factory.newThread(runnable);
                thread.setName(String.format(nameFormat, count.incrementAndGet()));
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    private static class MetricsSendRunnable implements Runnable {
        private final NewRelicMetricsReporter nrMetricsReporter;

        private MetricsSendRunnable(NewRelicMetricsReporter nrMetricsReporter) {
            this.nrMetricsReporter = nrMetricsReporter;
        }
        @Override
        public void run() {
            try {
                Map<String, Object> eventData = new HashMap<>();
                for (final Map.Entry<String, KafkaMetric> metric : nrMetricsReporter.getMetrics().entrySet()) {
                    Object metricValue = metric.getValue().metricValue();
                    if (metricValue instanceof Double) {
                        final float value = ((Double) metricValue).floatValue();
                        if (KAFKA_METRICS_DEBUG) {
                            AgentBridge.getAgent().getLogger().log(Level.FINEST, "getMetric: {0} = {1}", metric.getKey(), value);
                        }
                        if (!Float.isNaN(value) && !Float.isInfinite(value)) {
                            if (METRICS_AS_EVENTS) {
                                eventData.put(metric.getKey().replace('/', '.'), value);
                            } else {
                                NewRelic.recordMetric(METRIC_PREFIX + metric.getKey(), value);
                            }
                        }
                    }
                }
                if (METRICS_AS_EVENTS) {
                    for (NewRelicMetricsReporter.NodeMetricName nodeMetricName : nrMetricsReporter.getNodes().values()) {
                        eventData.put(nodeMetricName.asEventName(), 1f);
                    }
                } else {
                    for (NewRelicMetricsReporter.NodeMetricName nodeMetricName : nrMetricsReporter.getNodes().values()) {
                        NewRelic.recordMetric(nodeMetricName.getMetricName(), 1f);
                    }
                }
                if (METRICS_AS_EVENTS) {
                    NewRelic.getAgent().getInsights().recordCustomEvent(METRICS_EVENT_TYPE, eventData);
                }
            } catch (Exception e) {
                AgentBridge.getAgent().getLogger().log(Level.FINE, e, "Unable to record kafka metrics");
            }
        }
    }
}
