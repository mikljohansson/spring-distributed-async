package com.renable.api.distributed;

import com.renable.api.distributed.annotation.DistributedAsync;
import com.renable.api.distributed.annotation.DistributedScheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Service
public class DistributedTestProcessor {
    public static final AtomicInteger counter = new AtomicInteger(0);
    public static final AtomicInteger scheduledCounter = new AtomicInteger(0);

    @Autowired
    private DistributedProcessingService service;

    @Autowired
    private DistributedTestProcessor2 processor2;

    @DistributedAsync
    public void increment() {
        assertThat(service.isProcessingDistributedCall()).isTrue();
        sleepAndIncrement(counter, 1);
    }

    @DistributedAsync
    public void increment(ArrayList<Integer> amount) {
        assertThat(service.isProcessingDistributedCall()).isTrue();
        sleepAndIncrement(counter, amount.get(0));
    }

    @DistributedAsync
    public void incrementImmediately() {
        assertThat(service.isProcessingDistributedCall()).isTrue();
        counter.incrementAndGet();
    }

    @DistributedAsync(delay = "5")
    public void incrementDelayed(Integer amount) {
        assertThat(service.isProcessingDistributedCall()).isTrue();
        counter.addAndGet(amount);
    }

    @DistributedScheduled(fixedRate = 1000)
    public void scheduledIncrement() {
        assertThat(service.isProcessingDistributedCall()).isTrue();
        scheduledCounter.incrementAndGet();
    }
    
    @EventListener
    @DistributedAsync
    public void asyncEventHandler(IncrementDistributedTestEvent event) {
        assertThat(service.isProcessingDistributedCall()).isTrue();
        sleepAndIncrement(counter, event.getAmount());
    }

    @EventListener
    public void syncEventHandler(IncrementDistributedTestEvent event) {
        sleepAndIncrement(counter, event.getAmount());
    }

    @DistributedAsync
    public void incrementWithPayload(String[] payload) {
        assertThat(service.isProcessingDistributedCall()).isTrue();
        sleepAndIncrement(counter, 1);
    }

    private void sleepAndIncrement(AtomicInteger c, int amount) {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        c.addAndGet(amount);
    }

    @DistributedAsync
    public void incrementAndSend() {
        assertThat(service.isProcessingDistributedCall()).isTrue();

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // Need to do things in this order, to detect if processor2.increment() is done synchronously or not
        processor2.increment();

        counter.addAndGet(1);
    }
}
