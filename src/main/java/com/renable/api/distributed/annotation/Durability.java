package com.renable.api.distributed.annotation;

public enum Durability {
    /**
     * Message needs to be executed at least once
     */
    JOURNAL,

    /**
     * Message can be discarded in case of transient failures
     */
    TRANSIENT
};
