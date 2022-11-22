package com.renable.api.distributed;

public class IncrementDistributedTestEvent {
    public int amount;

    public IncrementDistributedTestEvent() {}

    public IncrementDistributedTestEvent(int amount) {
        this.amount = amount;
    }

    public int getAmount() {
        return amount;
    }
}
