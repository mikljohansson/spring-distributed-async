package com.renable.api.distributed.model;

import com.renable.api.distributed.annotation.Durability;

public class DistributedAsyncMessage {
    private String className;
    private String methodName;

    /**
     * Contains the JSON of the method call parameters. This is serialized so that we can discard
     * transient messages which fails when trying to deserialize the parameters.
     */
    private String parameters;

    /**
     * How durable this message needs to be, transient messages can be thrown away if they fail
     */
    private Durability durability = Durability.TRANSIENT;

    /**
     * Number of times this message has been retried
     */
    private int retryCount = 0;

    public DistributedAsyncMessage() {}

    public DistributedAsyncMessage(String className, String methodName, String parameters, Durability durability, int retryCount) {
        this.className = className;
        this.methodName = methodName;
        this.parameters = parameters;
        this.durability = durability;
        this.retryCount = retryCount;
    }

    public String getClassName() {
        return className;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getParameters() {
        return parameters;
    }

    public Durability getDurability() {
        return durability;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }
}
