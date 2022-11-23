# Spring-distributed-async

This library provides Spring annotations for distributed work queuing and scheduling. It allow you to annotate class 
methods have them execute on an auto-scaling container cluster. This is very similar to Spring's built-in @Async and 
@Scheduled annotations, but uses a pool of worker processes (e.g. Docker containers on a container cluster) to perform 
the actual work. 

```
@Service
public class MyService {
    @Getter
    @NoArgsConstructor
    public static class MyMessage {
        private String something;
        private List<SomePojo> items;      
    }
    
    @DistributedAsync
    public void processSomething(MyMessage message) {
        // ... method will be executed asyncronously by one of the workers
    }
    
    @DistributedScheduled(cron = "0 0 8 * * *")
    public void doSomethingEveryMorning() {
        // ... method will be executed by one of the workers every morning
    }
}
```

## Features

 * Methods are executed when called from another Spring component or based on a schedule (time interval or cron expression).
 * You can add delays to the method calls. For example to introduce jitter into your system and smooth our usage peaks.
 * You can selectively delay and retry processing of a message by throwing a special exception, for example if you want 
   to wait for something else to complete before continuing.
 * You can forms chains of @DistributedAsync calls, for example to create ETL pipelines. Take care that each step in the  
   chain resides in a separate Spring component, otherwise the magic Spring AOP method interception won't work and the call 
   will just execute blockingly on the current thread instead.
 * Unit tests can configure the library to execute the calls in a blocking way on the same, making it very easy to
   step through and debug your distributed methods or multi-stage ETL pipelines.

# Usage

After setting up your infrastructure you can just annotate Spring components methods with @DistributedAsync or
@DistributedScheduled and then distributed work processing will just happen, making for a very productive developer 
workflow.

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

@Service
public class MyService {
    @DistributedAsync
    public void processSomething(Integer amount, ArrayList<String> strings, MyMessage message) {
        ...
    }
}

@Controller
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
@Service
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
* You can remove attributes from message classes if you put `@JsonIgnoreProperties(ignoreUnknown = true)` on it.
* You can use other classes and interfaces inside your own message classes, anything that Jackson can (de)serialize with a TypeId will work.

You can also send concrete object/boxed types through as method parameters, for example `Integer` and `ArrayList` are fine. But `int` 
and `List` will not work as method parameters, because they're not concrete object types. This is due to mysterious deserialization 
and reflection reasons. 

## Delays

You can add fixed or random delays to the method calls, for example to introduce jitter into your system and smooth 
our usage peaks. Complex distributed system tend to like having some jitter here and there to improve robustness.

 * The maximum delay is 15 minutes (900 seconds) due to limitations in SQS

```
@Service
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

```
@Service
public class MyService {
    @DistributedAsync(durability = Durability.JOURNAL)
    public void processSomeImportantMessageThatCantBeLost(MyImportantMessage message) {
        ...
    }
}
```

## Backoff and retry

Sometimes you may want to retry a message at a later time, for example if you're waiting on another system to complete
something. In these cases it's not desirable to use a Thread.sleep() and block the worker thread, because that would 
throttle the throughput of the worker pool. Instead you can throw a `DistributedRetryException` which will cause
the message to be redelivered at a later time, and with exponential backoff.

Take care that the processing is eventually consistent, because your method will be retried from the start every time 
the message is redelivered.

```
@Service
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

## Async pipelines

You can create fan-out pipelines using chains of processing methods, for example to create ETL pipelines triggered 
on schedule or events.

```
@Service
public class FirstProcessor {
    @Autowired
    private SecondProcessor secondProcessor;
    
    // Trigger the ETL pipeline every hour
    @DistributedScheduled(cron = "0 0 * * * *")
    public void process() {
        // Extract items or chunks of data to process
        var extractedChunks = ...
        for (var chunk : extractedChunks) {
            secondProcessor.process(chunk);
        } 
    }
}

@Service
public class SecondProcessor {
    @Autowired
    private ThirdProcessor thirdProcessor;
    
    @DistributedAsync
    public void process(ExtractedChunk message) {
        // Process the chunks
        var transformedChunk = ...
        thirdProcessor.process(transformedChunk); 
    }
}

@Service
public class ThirdProcessor {
    @DistributedAsync
    public void process(TransformedChunk message) {
        // ... load into datastore 
    }
}
```

# Building and testing

You can build a jar like so

```
./gradlew jar
ls build/libs/spring-distributed-async-1.0.0-SNAPSHOT-plain.jar
```

And run the tests like this (or use IntelliJ to run the unit tests)

```
docker-compose up
./gradlew test
```

# Deployment

You should use the same Docker image or Springboot jar to deploy the workers, scheduler and your app server. This 
ensures that all the class names, method names and message definitions are always the same.

You'll need

* An (optionally auto-scaling) pool of worker processes/containers
* A single scheduler process/container
* Your regular app/rest/.. containers that sends messages to the workers
* An SQS message queue with a dead-letter queue attached
* A S3 bucket to spill large messages to (SQS supports a max of 256Kb messages)
* An AWS access key and secret access key

Put configuration into either .property resource files or environment variables. For example the AWS credentials 
should not go into .properties files or source control, AWS Secrets Manager can help here. Remember the Twelve-Factor 
App mantras.

## Workers
Deploy 1..n containers to act as the workers which processes message and performs the actual work. This pool of workers
could for example be an ECS Fargate service that auto-scales based on the current queue depth.

See `src/main/resources/application.properties` (base config) and `src/main/resources/application-worker.properties` 
(specific overrides for workers). 

## Scheduler
Deploy 1 container to act as the scheduler. There should only be 1 scheduler container running at a time or you'd 
get duplicated processing of @DistributedScheduled annotations. This container will not actually process any message, 
it'll only send out messages at the right times. So it will be mostly idle and will use very little memory/cpu. 

See `src/main/resources/application.properties` (base config) and `src/main/resources/application-scheduler.properties`
(specific overrides for the scheduler).

## Web or app servers

These are your regular app servers, they should use the same Docker image or Springboot jar as the workers and scheduler,
to ensure that all the class names, method names and message definitions are the same. 

See `src/main/resources/application.properties` for how to configure these.

## SQS Queue

Create a new SQS queue, for example `myapp-distributed-default` and give it these settings

* Set "visibility timeout" to longer than you expect any message to take to process, e.g. 15 minutes or whatever is good for your app
* Set "receive message wait time" to for example 20 seconds, to avoid excessive SQS GET calls to poll for messages on the queue
* Set "message retention period" to some time that is longer than what it takes you to discover and fix message processing issues, for example 14 days

Create a new dead-letter queue with the same settings, for example `myapp-distributed-default-deadletter` and then designate 
this as the dead-letter queue of the regular message queue. 

You may also want to configure some alerting on these queues, to notify you when your app stops processing messages for 
some reason. For example CloudWatch, Datadog or some other monitoring SaaS can do this. 

## S3 bucket

Create an S3 bucket where to spill large messages, for example `myapp-distributed-sqs`.

## AWS credentials

Create an IAM user with read and write access to the SQS queues and S3 bucket, then create a access key for this.
