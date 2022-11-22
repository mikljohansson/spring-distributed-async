package com.renable.api.distributed.annotation;

import org.springframework.core.annotation.AliasFor;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.annotation.*;

/**
 * DistributedRetryException can be thrown by your method and will cause the call to be retried at a later point
 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Scheduled
@DistributedAsync
public @interface DistributedScheduled {
    /** Initial delay to avoid sending messages on startup before all containers have restarted.
     * Must be set manually on annotation when needed. */
    long INITIAL_DELAY_FIXED_RATE = 600 * 1000;

    /**
     * Governs how the durability of messages should be handled
     */
    @AliasFor(annotation = DistributedAsync.class, attribute = "durability")
    Durability durability() default Durability.TRANSIENT;

    /**
     * A special cron expression value that indicates a disabled trigger: {@value}.
     * <p>This is primarily meant for use with ${...} placeholders, allowing for
     * external disabling of corresponding scheduled methods.
     * @since 5.1
     */
    String CRON_DISABLED = Scheduled.CRON_DISABLED;

    /**
     * A cron-like expression, extending the usual UN*X definition to include triggers
     * on the second as well as minute, hour, day of month, month and day of week.
     * <p>E.g. {@code "0 * * * * MON-FRI"} means once per minute on weekdays
     * (at the top of the minute - the 0th second).
     * <p>The special value {@link #CRON_DISABLED "-"} indicates a disabled cron trigger,
     * primarily meant for externally specified values resolved by a ${...} placeholder.
     * @return an expression that can be parsed to a cron schedule
     * @see org.springframework.scheduling.support.CronSequenceGenerator
     */
    @AliasFor(annotation = Scheduled.class, attribute = "cron")
    String cron() default "";

    /**
     * A time zone for which the cron expression will be resolved. By default, this
     * attribute is the empty String (i.e. the server's local time zone will be used).
     * @return a zone id accepted by {@link java.util.TimeZone#getTimeZone(String)},
     * or an empty String to indicate the server's default time zone
     * @since 4.0
     * @see org.springframework.scheduling.support.CronTrigger#CronTrigger(String, java.util.TimeZone)
     * @see java.util.TimeZone
     */
    @AliasFor(annotation = Scheduled.class, attribute = "zone")
    String zone() default "";

    /**
     * Execute the annotated method with a fixed period in milliseconds between
     * invocations.
     * @return the period in milliseconds
     */
    @AliasFor(annotation = Scheduled.class, attribute = "fixedRate")
    long fixedRate() default -1;

    /**
     * Number of milliseconds to delay before the first execution of a
     * {@link #fixedRate()} task.
     * @return the initial delay in milliseconds
     * @since 3.2
     */
    @AliasFor(annotation = Scheduled.class, attribute = "initialDelay")
    long initialDelay() default -1;
}
