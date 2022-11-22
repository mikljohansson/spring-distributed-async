package com.renable.api.distributed.annotation;

import java.lang.annotation.*;

/**
 * DistributedRetryException can be thrown by your method and will cause the call to be retried at a later point.
 *
 * NB:
 *
 * You muse use boxed types (Integer vs int) in your method signature, or reflection will fail.
 *
 * The caller and the called methods must be in separate classes, or proxying will fail and
 * the execution will happen in the caller's thread.
 *
 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedAsync {
    /**
     * Governs how the durability of messages should be handled
     */
    Durability durability() default Durability.TRANSIENT;

    /**
     * How many seconds to delay processing of this message, e.g for debouncing.
     *
     * This property supports resolution of spring @Value's, for example to specify the delay as a application
     * property you'd set it to "${myproperty.something}".
     *
     * Set to "random" to have a random delay between 0-900 seconds.
     */
    String delay() default "";
}
