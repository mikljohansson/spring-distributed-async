package com.renable.api.distributed;

import com.amazonaws.services.sqs.AmazonSQS;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.renable.api.distributed.annotation.DistributedAsync;
import com.renable.api.distributed.annotation.DistributedRetryException;
import com.renable.api.distributed.annotation.Durability;
import com.renable.api.distributed.model.DistributedAsyncMessage;
import org.apache.logging.log4j.util.Strings;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Aspect
@Component
public class DistributedProcessingService {
    private static final Logger LOG = LoggerFactory.getLogger(DistributedProcessingService.class);

    private static final int MAX_RETRY_COUNT = 10;

    @Value("${distributed.enabled:true}")
    private boolean enabled;

    @Value("${distributed.scheduler.enabled:true}")
    private boolean schedulerEnabled;

    @Value("${distributed.default.queue}")
    private String queueName;

    @Value("${distributed.default.deadletter}")
    private String deadletterQueueName;

    @Value("${distributed.maxRandomDelay:900}")
    private int maxRandomDelay;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AmazonSQS sqs;

    @Autowired
    private JmsTemplate defaultJmsTemplate;

    /**
     * Worker thread pool used to send messages to SQS in order to not block HTTP calls on SQS message sending
     */
    private final Executor senders = new ThreadPoolExecutor(
            1, 32, 5L, TimeUnit.MINUTES, new LinkedBlockingQueue<>());

    /**
     * Signals that calls through the passed through to the underlying method, instead of being intercepted
     * and queued. Thread local variable will hold different value for different threads.
     */
    private final ThreadLocal<List<DistributedAsyncMessage>> fallthrough = ThreadLocal.withInitial(ArrayList::new);

    /**
     * Used to serialize messages
     */
    private final ObjectMapper om = new ObjectMapper()
            .enableDefaultTyping(ObjectMapper.DefaultTyping.JAVA_LANG_OBJECT, JsonTypeInfo.As.PROPERTY);

    /**
     * @return  True if @DistributedScheduled annotates are enabled in this process.
     */
    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    /**
     * Verifies connectivity with SQS
     */
    public boolean getStatus() {
        return !Strings.isBlank(sqs.getQueueUrl(queueName).getQueueUrl())
                && !Strings.isBlank(sqs.getQueueUrl(deadletterQueueName).getQueueUrl());
    }

    /**
     * Used by unit tests to assert they're being called from a distributed message
     */
    protected boolean isProcessingDistributedCall() {
        return !fallthrough.get().isEmpty();
    }

    @Around("@annotation(com.renable.api.distributed.annotation.DistributedAsync) && @annotation(asyncAnnotation)")
    public Object aroundDistributedAsync(ProceedingJoinPoint joinPoint, DistributedAsync asyncAnnotation) throws Throwable {
        // Just pass through when processing the actual call of a message
        String className = joinPoint.getSignature().getDeclaringTypeName();
        String methodName = joinPoint.getSignature().getName();

        if (isProcessing(className, methodName)) {
            return joinPoint.proceed();
        }

        // Queue up the call for async processing
        String parameters = om.writer().writeValueAsString(joinPoint.getArgs());
        DistributedAsyncMessage message = new DistributedAsyncMessage(
                className, methodName, parameters, asyncAnnotation.durability(), 0);

        if (enabled) {
            // Queue up the call for async processing
            LOG.debug("Queuing call for {}.{}({}) as distributed processing message",
                    message.getClassName(), message.getMethodName(),
                    Arrays.stream(joinPoint.getArgs())
                            .map(item -> item != null ? item.getClass().getName() : "null")
                            .collect(Collectors.toList()));

            // Parse the number of seconds to delay the message
            long delaySeconds = 0;
            String delay = asyncAnnotation.delay();

            if (!Strings.isEmpty(delay)) {
                delay = context.getEnvironment().resolvePlaceholders(delay);

                if ("random".equals(delay)) {
                    delaySeconds = new Random().nextInt(maxRandomDelay);
                }
                else {
                    delaySeconds = Long.parseLong(delay);
                }
            }

            if (asyncAnnotation.durability() == Durability.JOURNAL) {
                // Need to commit to queue as a synchronous operation in order to guarantee persistence
                send(message, delaySeconds, false);
            }
            else {
                // Transient messages can be offloaded onto sending workers, since it's ok if they fail
                long finalDelaySeconds = delaySeconds;
                senders.execute(() -> send(message, finalDelaySeconds, false));
            }
        }
        else {
            // Unittests will execute the message immediately to avoid conflicts between different tests
            LOG.debug("Executing call for {}.{}({}) directly because distributed.enabled=false",
                    message.getClassName(), message.getMethodName(),
                    Arrays.stream(joinPoint.getArgs()).map(item -> item.getClass().getName()).collect(Collectors.toList()));
            receiveDistributedAsync(message);
        }

        return null;
    }

    private boolean isProcessing(String className, String methodName) {
        List<DistributedAsyncMessage> stack = fallthrough.get();
        if (!stack.isEmpty()
                && stack.get(stack.size() - 1).getClassName().equals(className)
                && stack.get(stack.size() - 1).getMethodName().equals(methodName)) {
            return true;
        }

        return false;
    }

    private void send(DistributedAsyncMessage message, long delaySeconds, boolean deadletter) {
        // Uses ThreadSafeJmsTemplate which stores the delivery delay in a thread local variable
        if (delaySeconds > 0) {
            // SQS supports a maximum of 15 minutes delay on messages
            defaultJmsTemplate.setDeliveryDelay(Math.min(delaySeconds, 900) * 1000);
        }
        else {
            defaultJmsTemplate.setDeliveryDelay(-1);
        }

        defaultJmsTemplate.convertAndSend(deadletter ? deadletterQueueName : queueName, message);
    }

    @Around("@annotation(com.renable.api.distributed.annotation.DistributedScheduled)")
    public Object aroundDistributedScheduled(ProceedingJoinPoint joinPoint) throws Throwable {
        // Just pass through when processing the actual call of a message
        String className = joinPoint.getSignature().getDeclaringTypeName();
        String methodName = joinPoint.getSignature().getName();

        if (isProcessing(className, methodName)) {
            return joinPoint.proceed();
        }

        MethodSignature signature = (MethodSignature)joinPoint.getSignature();
        DistributedAsync asyncAnnotation = MergedAnnotations
                .from(signature.getMethod(), MergedAnnotations.SearchStrategy.INHERITED_ANNOTATIONS)
                .get(DistributedAsync.class)
                .synthesize();

        if (schedulerEnabled) {
            LOG.info("Queuing @DistributedScheduled call for {}.{}({})",
                    signature.getDeclaringTypeName(), signature.getMethod().getName(),
                    Arrays.stream(joinPoint.getArgs()).map(item -> item.getClass().getName()).collect(Collectors.toList()));
            return aroundDistributedAsync(joinPoint, asyncAnnotation);
        }

        return null;
    }

    @JmsListener(destination = "${distributed.default.queue}")
    public void receiveDistributedAsync(DistributedAsyncMessage message) {
        try {
            process(message);
        }
        catch (DistributedRetryException e) {
            retry(message, false, e);
        }
    }

    /**
     * Reprocess deadletters but with backoff
     */
    @JmsListener(destination = "${distributed.default.deadletter}")
    public void receiveDistributedAsyncDeadletter(DistributedAsyncMessage message) throws InterruptedException {
        // Just discard transient messages, the system will retry at some later point anyways
        if (message.getDurability() == Durability.TRANSIENT) {
            return;
        }

        try {
            process(message);
        }
        catch (DistributedRetryException e) {
            retry(message, true, e);
        }
        catch (Exception e) {
            // Backoff a bit so we don't busy loop trying to process poison pill messages
            Thread.sleep((int)(60 * 1000 * (0.5 + 0.5 * new Random().nextDouble())));
            throw new RuntimeException("Failed to execute deadlettered message", e);
        }
    }

    private void retry(DistributedAsyncMessage message, boolean deadletter, DistributedRetryException e) {
        // Message should be retried a few times, as well as those coming off the deadletter queue should be retried
        if (message.getRetryCount() < MAX_RETRY_COUNT || deadletter) {
            message.setRetryCount(message.getRetryCount() + 1);
            LOG.info("Distributed async message will be retried again, this was delivery attempt {}. {}",
                    message.getRetryCount(), e.getMessage());
            send(message, backoffWithJitter(message.getRetryCount()), deadletter);
        }
        else if (message.getDurability() == Durability.TRANSIENT) {
            // Transient messages can just be discarded without deadlettering, so just ack the message without doing anything
            LOG.warn("Transient distributed async message reached the maximum number retries ({}) and will be discarded", MAX_RETRY_COUNT, new RuntimeException(e));
        }
        else {
            // We're processing a Durability.JOURNAL deadletter, so should error out and continue to try and retry it
            throw new RuntimeException(String.format("Distributed async message reached the maximum number retries (%d) and will be dead lettered", MAX_RETRY_COUNT), e);
        }
    }

    private long backoffWithJitter(int retryCount) {
        return (long) (Math.min(10L * Math.pow(2L, retryCount), maxRandomDelay) * (0.5 + 0.5 * new Random().nextDouble()));
    }

    private void process(DistributedAsyncMessage message) {
        List<DistributedAsyncMessage> stack = fallthrough.get();
        stack.add(message);

        try {
            LOG.debug("Executing call for {}.{}(...) received from distributed processing message",
                    message.getClassName(), message.getMethodName());
            Object[] parameters = om.readValue(message.getParameters(), Object[].class);
            invoke(message.getClassName(), message.getMethodName(), parameters);
        }
        catch (IOException e) {
            LOG.error("Failed to call {}.{}(...) from distributed processing message",
                    message.getClassName(), message.getMethodName(), e);
            throw new RuntimeException(e);
        }
        finally {
            stack.remove(stack.size() - 1);
        }
    }

    protected void invoke(String className, String methodName, Object[] parameters) {
        try {
            Class<?>[] parameterTypes = new Class<?>[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                parameterTypes[i] = parameters[i].getClass();
            }

            Class<?> targetClass = ClassUtils.forName(className, context.getClassLoader());
            Object targetObject = context.getBean(targetClass);
            if (targetObject == null) {
                LOG.error(
                        "Failed to call {}.{}({}) from distributed processing message because bean {} could not be found",
                        className, methodName, parameters, className);
                throw new RuntimeException(String.format(
                        "Failed to call %s.%s(%s) from distributed processing message because bean %s could not be found",
                        className, methodName, Arrays.toString(parameters), className));
            }

            // Resolve targetMethod from targetObject.getClass() instead of from targetClass, since Spring
            // will create a transparent proxy it won't be the same as targetClass. We want to call the
            // transparent proxy method, to ensure that other annotations like @Transactional also works.
            Method targetMethod = targetClass.getMethod(methodName, parameterTypes);
            targetMethod.invoke(targetObject, parameters);
        }
        catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            if (e.getCause() instanceof DistributedRetryException) {
                throw (DistributedRetryException)e.getCause();
            }

            LOG.error("Failed to call {}.{}({}) from distributed processing message",
                    className, methodName, parameters, e);
            throw new RuntimeException(String.format(
                    "Failed to call %s.%s(%s) from distributed processing message",
                    className, methodName, Arrays.toString(parameters)), e);
        }
    }
}
