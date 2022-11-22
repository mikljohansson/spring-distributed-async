package com.renable.api.distributed;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.PurgeQueueRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@SpringBootTest
@ActiveProfiles("distributedtest")
public class DistributedProcessingServiceTest {
    private static final int TIMEOUT_MILLIS = 15000;
    
    @Autowired
    private DistributedProcessingService service;

    @Autowired
    private DistributedTestProcessor testProcessor;

    @Autowired
    private DistributedTestProcessor2 testProcessor2;

    @Value("${aws.sqs.endpoint}/000000000000/${distributed.default.queue}")
    private String sqsQueueUrl;

    @Autowired
    AmazonSQS sqs;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @BeforeEach
    public void setUp() {
        sqs.purgeQueue(new PurgeQueueRequest(sqsQueueUrl));
    }

    @Test
    public void testStatus() {
        assertThat(service.getStatus()).isTrue();
    }

    @Test
    public void testDistributedAsync() throws InterruptedException {
        int c1 = testProcessor.counter.get();
        testProcessor.increment();

        int c2 = testProcessor.counter.get();
        assertThat(c2).isEqualTo(c1);

        long ts = System.currentTimeMillis();
        while (testProcessor.counter.get() == c2) {
            Thread.sleep(25);

            if (System.currentTimeMillis() - ts > TIMEOUT_MILLIS) {
                fail("Timed out while waiting for message to be processed");
            }
        }

        int c3 = testProcessor.counter.get();
        assertThat(c3).isEqualTo(c1 + 1);
    }

    @Test
    public void testCollectionParameter() throws InterruptedException {
        int c1 = testProcessor.counter.get();
        ArrayList<Integer> amount = new ArrayList<>();
        amount.add(1);
        testProcessor.increment(amount);

        int c2 = testProcessor.counter.get();
        assertThat(c2).isEqualTo(c1);

        long ts = System.currentTimeMillis();
        while (testProcessor.counter.get() == c2) {
            Thread.sleep(25);

            if (System.currentTimeMillis() - ts > TIMEOUT_MILLIS) {
                fail("Timed out while waiting for message to be processed");
            }
        }

        int c3 = testProcessor.counter.get();
        assertThat(c3).isEqualTo(c1 + 1);
    }

    @Test
    public void testDistributedScheduled() throws InterruptedException {
        int c1 = testProcessor.scheduledCounter.get();
        Thread.sleep(300);

        long ts = System.currentTimeMillis();
        while (testProcessor.scheduledCounter.get() == c1) {
            Thread.sleep(25);

            if (System.currentTimeMillis() - ts > TIMEOUT_MILLIS) {
                fail("Timed out while waiting for message to be processed");
            }
        }

        int c2 = testProcessor.scheduledCounter.get();
        assertThat(c2).isNotEqualTo(c1);
    }
    
    @Test
    public void testDistributedAsyncEvents() throws InterruptedException {
        int c1 = testProcessor.counter.get();
        applicationEventPublisher.publishEvent(new IncrementDistributedTestEvent(2));

        int c2 = testProcessor.counter.get();
        assertThat(c2).isEqualTo(c1 + 2);

        long ts = System.currentTimeMillis();
        while (testProcessor.counter.get() == c2) {
            Thread.sleep(25);

            if (System.currentTimeMillis() - ts > TIMEOUT_MILLIS) {
                fail("Timed out while waiting for message to be processed");
            }
        }

        int c3 = testProcessor.counter.get();
        assertThat(c3).isEqualTo(c1 + 4);
    }

    @Test
    public void testLargeMessageSpillsToS3() throws InterruptedException {
        String[] payload = new String[1024 * 1024];

        // Fill with random bytes in case there's payload compression going on
        Random random = new Random(12345);
        for (int i = 0; i < payload.length; i++) {
            payload[i] = String.valueOf((char)(1 + random.nextInt(255)));
        }

        int c1 = testProcessor.counter.get();
        testProcessor.incrementWithPayload(payload);

        int c2 = testProcessor.counter.get();
        assertThat(c2).isEqualTo(c1);

        long ts = System.currentTimeMillis();
        while (testProcessor.counter.get() == c2) {
            Thread.sleep(25);

            if (System.currentTimeMillis() - ts > TIMEOUT_MILLIS) {
                fail("Timed out while waiting for message to be processed");
            }
        }

        int c3 = testProcessor.counter.get();
        assertThat(c3).isEqualTo(c1 + 1);
    }

    @Test
    public void testDistributedAsyncThatCallsDistributedAsync() throws InterruptedException {
        int c1 = testProcessor.counter.get();
        int d1 = testProcessor2.counter.get();
        testProcessor.incrementAndSend();

        int c2 = testProcessor.counter.get();
        int d2 = testProcessor2.counter.get();
        assertThat(c2).isEqualTo(c1);
        assertThat(d2).isEqualTo(d1);

        long ts = System.currentTimeMillis();
        while (testProcessor.counter.get() == c2) {
            Thread.sleep(25);

            if (System.currentTimeMillis() - ts > TIMEOUT_MILLIS) {
                fail("Timed out while waiting for message to be processed");
            }
        }

        int d3 = testProcessor2.counter.get();
        assertThat(d3).isEqualTo(d1);

        ts = System.currentTimeMillis();
        while (testProcessor2.counter.get() == d3) {
            Thread.sleep(25);

            if (System.currentTimeMillis() - ts > TIMEOUT_MILLIS) {
                fail("Timed out while waiting for message to be processed");
            }
        }

        int d4 = testProcessor2.counter.get();
        assertThat(d4).isEqualTo(d1 + 1);
    }

    @Test
    public void testDelay() throws InterruptedException {
        int c1 = testProcessor.counter.get();
        testProcessor.incrementDelayed(2);
        testProcessor.incrementImmediately();

        int c2 = testProcessor.counter.get();
        assertThat(c2).isEqualTo(c1);

        long ts = System.currentTimeMillis();
        while (testProcessor.counter.get() == c2) {
            Thread.sleep(25);

            if (System.currentTimeMillis() - ts > TIMEOUT_MILLIS) {
                fail("Timed out while waiting for first message to be processed");
            }
        }

        int c3 = testProcessor.counter.get();
        assertThat(c3).isEqualTo(c1 + 1);

        ts = System.currentTimeMillis();
        while (testProcessor.counter.get() == c3) {
            Thread.sleep(25);

            if (System.currentTimeMillis() - ts > TIMEOUT_MILLIS) {
                fail("Timed out while waiting for delayed message to be processed");
            }
        }

        int c5 = testProcessor.counter.get();
        assertThat(c5).isEqualTo(c3 + 2);
        assertThat(System.currentTimeMillis() - ts).isGreaterThan(1000);
    }
}
