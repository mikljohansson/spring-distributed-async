package com.renable.api.distributed;

import com.renable.api.distributed.annotation.DistributedAsync;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Service
public class DistributedTestProcessor2 {
    public static final AtomicInteger counter = new AtomicInteger(0);

    @Autowired
    private DistributedProcessingService service;

    @DistributedAsync
    public void increment() {
        assertThat(service.isProcessingDistributedCall()).isTrue();
        sleepAndIncrement(1);
    }

    private void sleepAndIncrement(int amount) {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        counter.addAndGet(amount);
    }
}
