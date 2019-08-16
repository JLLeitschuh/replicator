package com.booking.replication.flink;

import com.booking.replication.augmenter.model.event.AugmentedEvent;
import com.booking.replication.commons.metrics.Metrics;
import com.booking.replication.controller.WebServer;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import org.apache.flink.api.common.functions.Partitioner;
import org.apache.flink.runtime.state.filesystem.FsStateBackend;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class ReplicatorFlinkApplication {

    public interface Configuration {
        String CHECKPOINT_PATH = "checkpoint.path";
        String CHECKPOINT_DEFAULT = "checkpoint.default";
        String REPLICATOR_THREADS = "replicator.threads";
        String REPLICATOR_TASKS = "replicator.tasks";
        String REPLICATOR_QUEUE_SIZE = "replicator.queue.size";
        String REPLICATOR_QUEUE_TIMEOUT = "replicator.queue.timeout";
        String OVERRIDE_CHECKPOINT_START_POSITION = "override.checkpoint.start.position";
        String OVERRIDE_CHECKPOINT_BINLOG_FILENAME = "override.checkpoint.binLog.filename";
        String OVERRIDE_CHECKPOINT_BINLOG_POSITION = "override.checkpoint.binLog.position";
        String OVERRIDE_CHECKPOINT_GTID_SET = "override.checkpoint.gtidSet";
    }

    private static final Logger LOG = LogManager.getLogger(com.booking.replication.Replicator.class);
    private static final String COMMAND_LINE_SYNTAX = "java -jar mysql-replicator-<version>.jar";

    private final String checkpointDefault;
    private final Metrics<?> metrics;
    private final String errorCounter;
    private final WebServer webServer;
    private final AtomicLong checkPointDelay;

    private final StreamExecutionEnvironment env;
    private BinlogSource source;
    private ReplicatorGenericFlinkDummySink sink;

    private final String METRIC_COORDINATOR_DELAY               = MetricRegistry.name("coordinator", "delay");
    private final String METRIC_STREAM_DESTINATION_QUEUE_SIZE   = MetricRegistry.name("streams", "destination", "queue", "size");
    private final String METRIC_STREAM_SOURCE_QUEUE_SIZE        = MetricRegistry.name("streams", "source", "queue", "size");

    public ReplicatorFlinkApplication(final Map<String, Object> configuration) {

        Object checkpointPath = configuration.get(com.booking.replication.flink.ReplicatorFlinkApplication.Configuration.CHECKPOINT_PATH);
        Object checkpointDefault = configuration.get(com.booking.replication.flink.ReplicatorFlinkApplication.Configuration.CHECKPOINT_DEFAULT);

        Objects.requireNonNull(checkpointPath, String.format("Configuration required: %s", com.booking.replication.flink.ReplicatorFlinkApplication.Configuration.CHECKPOINT_PATH));

        this.checkpointDefault = (checkpointDefault != null) ? (checkpointDefault.toString()) : (null);

        this.webServer = WebServer.build(configuration);

        this.metrics = Metrics.build(configuration, webServer.getServer());

        this.errorCounter = MetricRegistry.name(
                String.valueOf(configuration.getOrDefault(Metrics.Configuration.BASE_PATH, "replicator")),
                "error"
        );

        this.checkPointDelay = new AtomicLong(0L);

        this.metrics.register(METRIC_COORDINATOR_DELAY, (Gauge<Long>) () -> this.checkPointDelay.get());

        env = StreamExecutionEnvironment.createLocalEnvironment();

        env.enableCheckpointing(1000).setStateBackend(
                new FsStateBackend("file:///tmp",
                        false)
        );

        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);

        env.getCheckpointConfig()
                .enableExternalizedCheckpoints(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);

        try {

            this.source = new BinlogSource(configuration);

            DataStream<AugmentedEvent> augmentedEventDataStream =
                    env.addSource(source).forceNonParallel();

            Partitioner<AugmentedEvent> binlogEventFlinkPartitioner =
                    BinlogEventFlinkPartitioner.build(configuration);

            DataStream<AugmentedEvent> partitionedDataStream =
                    augmentedEventDataStream
                            .partitionCustom(
                                    binlogEventFlinkPartitioner,
                                    // binlogEventPartitioner knows how to convert event to partition,
                                    // so there is no need for a separate KeySelector
                                    event -> event // <- identity key selector
                            );

            RichSinkFunction<AugmentedEvent>  x = new ReplicatorGenericFlinkDummySink(configuration);

            partitionedDataStream.addSink(x);


        } catch (IOException exception) {
            exception.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void start() throws Exception {

        System.out.println("hohoho");

        ReplicatorFlinkApplication.LOG.info("starting webserver");

        try {
            this.webServer.start();
        } catch (IOException e) {
            ReplicatorFlinkApplication.LOG.error("error starting webserver", e);
        }

        System.out.println("Execution plan => " + env.getExecutionPlan());

        env.execute("Replicator");

        ReplicatorFlinkApplication.LOG.info("Flink env started");
    }

    public void stop() {
        try {

            ReplicatorFlinkApplication.LOG.info("Stopping Binlog Flink Source");
            if (this.source != null) {
                this.source.cancel();
            }

            ReplicatorFlinkApplication.LOG.info("closing sink");
            if (this.sink != null) {
                this.sink.close();
            }

            ReplicatorFlinkApplication.LOG.info("stopping web server");
            this.webServer.stop();

            ReplicatorFlinkApplication.LOG.info("closing metrics sink");
            this.metrics.close();

        } catch (IOException exception) {
            ReplicatorFlinkApplication.LOG.error("error stopping coordinator", exception);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
