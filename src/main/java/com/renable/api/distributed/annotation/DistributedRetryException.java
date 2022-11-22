package com.renable.api.distributed.annotation;

public class DistributedRetryException extends RuntimeException {
    public DistributedRetryException(String message) {
        super(message);
    }
}
