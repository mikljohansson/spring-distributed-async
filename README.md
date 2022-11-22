# Spring Distributed Async

This library works very similar to Spring's built-in @Async and @Scheduled annotations, but uses a pool of worker
processes (e.g. Docker containers) to perform the actual work. This allows you to very easily offload processing 
of some methods to an auto-scaling container cluster. 

 * Methods are executed when called from another Spring component or based on a schedule (time interval or cron expression).
 * You can add delays to the method calls. For example to introduce jitter into your system and smooth our usage peaks.
 * You can selectively delay and retry processing of a message by throwing a special exception, for example if you want 
   to wait for something else to complete before continuing.
 * You can forms chains of @DistributedAsync calls, for example to create ETL pipelines. Take care that each step in the  
   chain resides in a separate Spring component, otherwise the magic AOP method interception won't work and the call will
   just execute blockingly on the current thread instead.

# Usage

## @DistributedAsync

This annotation works much like the Spring @Async annotation, but executes the method call on the pool of 
worker containers instead of on a worker thread. For more information see the Spring documentation for @Async.  

```
public class SomePojo {
    ...
}

public class MyMessage {
    private int field1;
    private String field2;
    private SomePojo somePojo;

    /**
     * Need a default constructor for Jackson
     */
    public MyMessage() {}
    
    public MyMessage(int field1, String field2, SomePojo somePojo) {
        this.field1 = field1;
        this.field2 = field2;
        this.somePojo = somePojo.
    }
}

public class MyService {
    @DistributedAsync
    public void processSomething(Integer amount, ArrayList<String> strings, MyMessage message) {
        ...
    }
}

public class MyController {
    @Autowired
    private MyService myService;

    public void someRestControllerMethod() {
        myService.processSomething(
            15, 
            new ArrayList(List.of("abc", "123")), 
            new MyMessage(1, "2", new SomePojo())
        );
    }
}
```

## @DistributedScheduled

This annotation works like the Spring @Scheduled annotation, but executes the method call on the pool of
worker containers instead of on a worker thread. For more information see the Spring documentation for @Scheduled.

```
public class MyService {
    @DistributedScheduled(fixedRate = 60 * 60 * 1000, initialDelay = DistributedScheduled.INITIAL_DELAY_FIXED_RATE)
    public void doSomethingEveryHour() {
        ...    
    }
    
    @DistributedScheduled(cron = "0 0 8 * * *")
    public void doSomethingEveryMorning() {
        ...    
    }
}
```

## Messages

Messages are Java POJO classes which are serialized to JSON using Jackson object mapper. This means that

* You can extend message classes with new attributes if you add default values for those fields
* You can remove attributes from message classes if you put `@JsonIgnoreProperties(ignoreUnknown = true)` on the message class.
* You can use other classes and interfaces inside your own message classes, anything that Jackson can deserialize will work.

You can also send concrete object/boxed types through as method parameters, for example `Integer` and `ArrayList` are fine. But `int` 
and `List` will not work as method parameters, because they're not concrete object types. This is due to mysterious deserialization 
and reflection reasons. 

## Delays

You can add delays to the method calls, for example to introduce jitter into your system and smooth our usage peaks.

 * The maximum delay is 15 minutes (900 seconds) due to limitations in SQS

```
public class MyService {
    /**
     * The processing of this async call will be delayed 60 seconds.
     */
    @DistributedAsync(delay = "60")
    public void processSomething(MyMessage message) {
        ...
    }

    /**
     * The processing of this async call will be delayed between 0 and 900 seconds at random.
     */
    @DistributedAsync(delay = "random")
    public void processSomething(MyMessage message) {
        ...
    }
}
```

## Durability

You can control the durability of messages, for example to differentiate between messages that are ok if they're 
lost (e.g. since they'll be retried or be eventually consisted anyways) or those that needs to be processed at least once.

Messages that cause delivery errors (e.g. exceptions) will be sent to the dead-letter queue by SQS. The workers will try 
then to reprocess dead-lettered messages in ways that are governed by the `durability` parameter. `durability=TRANSIENT` 
messages will just be discarded from the dead-letter queue, but `durability=JOURNAL` messages will be retried forever 
and with exponential backoff.

By default all messages are `durability=TRANSIENT`. If you code your application to be self-healing and eventually 
consistent then this makes for very easy operations indeed. Just fix the problem that caused message processing to fail
and deploy and new version of you app and sit back and wait while things self-heals.   

Take care that the processing is eventually consistent, because your method will be retried from the start every time
a `durability=JOURNAL` message is redelivered.

## Backoff and retry

Sometimes you may want to retry a message at a later time, for example if you're waiting on another system to complete
something. In these cases it's not desirable to use a Thread.sleep() and block the worker thread, because that would 
throttle the throughput of the worker pool. Instead you can throw a `DistributedRetryException` which will cause
the message to be redelivered at a later time, and with exponential backoff.

Take care that the processing is eventually consistent, because your method will be retried from the start every time 
the message is redelivered.

```
public class MyService {
    @DistributedAsync
    public void processSomething(MyMessage message) {
        // ... start processing message
        
        if (!otherSystem.isIsReady(message)) {
            throw new DistributedRetryException("Waiting for other system to complete something");
        }
        
        // ... continue processing message
    }
}
```

# Deployment

You should use the same Docker image or Springboot jar to deploy the workers, scheduler and your app server. This to 
ensures that all the class names, method names and message definitions are always the same. 

## Workers
Deploy 1..n containers to act as the workers which processes message and performs the actual work. This pool of workers
could for example be an ECS Fargate service that auto-scales based on the current queue depth.

See application-worker.properties for how to configure these instances.  

## Scheduler
Deploy 1 container to act as the scheduler. There should only be 1 scheduler container running at a time or you'd 
get duplicated processing of @DistributedScheduled annotations. This container will not actually process any message, 
it'll only send out messages at the right times. So it will be mostly idle and will use very little memory/cpu. 

See application-scheduler.properties for how to configure this instance

## Web or app servers

These are your regular app servers, they should use the same Docker image or Springboot jar as the workers and scheduler,
to ensure that all the class names, method names and message definitions are the same. 

See application.properties for how to configure these.
