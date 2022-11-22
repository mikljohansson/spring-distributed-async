package com.renable.api.distributed;

import com.renable.api.distributed.annotation.DistributedScheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.util.Assert;

import java.lang.reflect.Method;

/**
 * Allows us to intercept the runnable that calls the target method, and instead send a distributed message.
 */
public class DistributedScheduledAnnotationBeanPostProcessor extends ScheduledAnnotationBeanPostProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(DistributedScheduledAnnotationBeanPostProcessor.class);
    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        super.setApplicationContext(applicationContext);
        this.applicationContext = applicationContext;
    }

    @Override
    protected Runnable createRunnable(Object target, Method method) {
        DistributedScheduled scheduledAnnotation = AnnotationUtils.findAnnotation(method, DistributedScheduled.class);
        if (scheduledAnnotation != null) {
            // See ScheduledAnnotationBeanPostProcessor.createRunnable()
            Assert.isTrue(method.getParameterCount() == 0, "Only no-arg methods may be annotated with @Scheduled");
            Method invocableMethod = AopUtils.selectInvocableMethod(method, target.getClass());
            DistributedProcessingService service = applicationContext.getBean(DistributedProcessingService.class);

            // Only one container replica should have the scheduler enabled, all other worker containers will do
            // nothing when the @DistributedScheduled method is invoked by @Scheduled
            if (service.isSchedulerEnabled()) {
                return new ScheduledMethodRunnable(target, invocableMethod) {
                    @Override
                    public void run() {
                        // Call the method through the transparent proxy Spring creates, instead of by calling the method directly
                        // like ScheduledMethodRunnable does. This will cause @DistributedAsync to be intercepted like usual and
                        // the call sent as a message instead.
                        service.invoke(invocableMethod.getDeclaringClass().getName(), invocableMethod.getName(), new Object[] {});
                    }
                };
            }

            LOG.debug(
                    "Ignoring call to @DistributedScheduled method {}.{}() because distributed.scheduler = false",
                    method.getDeclaringClass().getName(), method.getName());
            return new ScheduledMethodRunnable(target, invocableMethod) {
                @Override
                public void run() {}
            };
        }

        // Not a @DistributedAsync method, so just return the normal ScheduledMethodRunnable
        return super.createRunnable(target, method);
    }
}
