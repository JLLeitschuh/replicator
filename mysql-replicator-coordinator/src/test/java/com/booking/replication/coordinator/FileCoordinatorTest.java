package com.booking.replication.coordinator;

import com.booking.replication.commons.checkpoint.Checkpoint;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class FileCoordinatorTest {
    private static AtomicInteger count;
    private static Coordinator coordinator1;
    private static Coordinator coordinator2;

    @BeforeClass
    public static void before() throws InterruptedException {
        FileCoordinatorTest.count = new AtomicInteger();

        Runnable leadershipTake = () -> {
            FileCoordinatorTest.count.getAndIncrement();

            try {
                Thread.sleep(500L);
            } catch (InterruptedException exception) {
                throw new RuntimeException(exception);
            }
        };

        Runnable leaderShipLoss = () -> {
            assertEquals(1, FileCoordinatorTest.count.get());

            FileCoordinatorTest.count.getAndDecrement();
        };

        FileCoordinatorTest.coordinator1 = new FileCoordinator(Collections.singletonMap(Coordinator.Configuration.TYPE, Coordinator.Type.FILE.name()));
        FileCoordinatorTest.coordinator1.onLeadershipTake(leadershipTake);
        FileCoordinatorTest.coordinator1.onLeadershipLoss(leaderShipLoss);
        FileCoordinatorTest.coordinator1.start();

        FileCoordinatorTest.coordinator2 = new FileCoordinator(Collections.singletonMap(Coordinator.Configuration.TYPE, Coordinator.Type.FILE.name()));
        FileCoordinatorTest.coordinator2.onLeadershipTake(leadershipTake);
        FileCoordinatorTest.coordinator2.onLeadershipLoss(leaderShipLoss);
        FileCoordinatorTest.coordinator2.start();
    }

    @Test
    public void testLeadership() throws InterruptedException {
        Thread.sleep(2000L);

        assertEquals(1, this.count.get());
    }

    @Test
    public void testCheckpoint() throws InterruptedException, IOException {
        Thread.sleep(2000L);

        Checkpoint checkpoint1 = new Checkpoint(
                ThreadLocalRandom.current().nextLong(),
                UUID.randomUUID().toString(),
                ThreadLocalRandom.current().nextLong(),
                UUID.randomUUID().toString(),
                ThreadLocalRandom.current().nextInt()
        );

        FileCoordinatorTest.coordinator1.saveCheckpoint("/tmp/checkpoint", checkpoint1);

        Checkpoint checkpoint2 = FileCoordinatorTest.coordinator1.loadCheckpoint("/tmp/checkpoint");

        assertEquals(checkpoint1, checkpoint2);
    }

    @AfterClass
    public static void after() throws InterruptedException {
        FileCoordinatorTest.coordinator1.stop();
        FileCoordinatorTest.coordinator2.stop();

        assertEquals(0, FileCoordinatorTest.count.get());
    }
}