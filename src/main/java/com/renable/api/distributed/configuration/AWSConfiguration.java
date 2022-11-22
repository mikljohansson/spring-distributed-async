
package com.renable.api.distributed.configuration;

import com.amazon.sqs.javamessaging.AmazonSQSExtendedClient;
import com.amazon.sqs.javamessaging.ExtendedClientConfiguration;
import com.amazon.sqs.javamessaging.ProviderConfiguration;
import com.amazon.sqs.javamessaging.SQSConnectionFactory;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSAsyncClient;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import org.apache.logging.log4j.util.Strings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Class that sets up the configuration for communicating with different services on AWS
 */
@Configuration
public class AWSConfiguration {

    /**
    * IAM user access key from AWS. 20 character string
    * 
    * Example: AKI32ELTRA
    */
	@Value("${aws.credentials.accessKey}")
    private String accessKey;
    
    /**
    * IAM user secret key from AWS. 40 character string
    * 
    * Example: HgkclnqnHRnf57Ie...
    */
    @Value("${aws.credentials.secretKey}")
    private String secretKey;
    
    /**
    * Region for AWS
    * 
    * Example: eu-west-1
    */
    @Value("${aws.region}")
    private String region;

    /**
     * Endpoint for AWS SQS. Usually only used when developing against Localstack
     *
     * Example: http://localhost:4566/
     */
    @Value("${aws.sqs.endpoint:#{''}}")
    private String sqsEndpoint;

    @Value("${aws.sqs.prefetch:1}")
    private int sqsPrefetch;

    @Value("${aws.sqs.bucket}")
    private String sqsBucketName;

    /**
    * Sets up the basic credentials required for communicating with AWS
    * 
    * @return
    */
    @Bean
    public BasicAWSCredentials basicAWSCredentials() {
        return new BasicAWSCredentials(accessKey, secretKey);
    }

    @Bean
    public AmazonSQS awsSQSService(AWSCredentials awsCredentials) {
        AmazonS3 s3 = getAmazonS3(awsCredentials, sqsEndpoint);

        AmazonSQSClientBuilder builder = AmazonSQSAsyncClient.builder()
                .withCredentials(new AWSStaticCredentialsProvider(awsCredentials));

        if (!Strings.isBlank(sqsEndpoint)) {
            builder.setEndpointConfiguration(new EndpointConfiguration(sqsEndpoint, region));
        }
        else {
            builder.setRegion(region);
        }

        // Spill large messages (more than 256kb) over to S3
        // https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-s3-messages.html
        ExtendedClientConfiguration extendedConfig = new ExtendedClientConfiguration()
                .withPayloadSupportEnabled(s3, sqsBucketName);
        return new AmazonSQSExtendedClient(builder.build(), extendedConfig);
    }

    private AmazonS3 getAmazonS3(AWSCredentials awsCredentials, String endpoint) {
        AmazonS3ClientBuilder s3ClientBuilder = AmazonS3ClientBuilder.standard()
                .withCredentials(new AWSStaticCredentialsProvider(awsCredentials))
                .withPathStyleAccessEnabled(true);

        if (!Strings.isBlank(endpoint)) {
            s3ClientBuilder.setEndpointConfiguration(new EndpointConfiguration(endpoint, region));
        }
        else {
            s3ClientBuilder.setRegion(region);
        }

        return s3ClientBuilder.build();
    }

    @Bean
    public SQSConnectionFactory awsSQSConnectionFactory(AmazonSQS sqs) {
        ProviderConfiguration config = new ProviderConfiguration()
                .withNumberOfMessagesToPrefetch(sqsPrefetch);
        return new SQSConnectionFactory(config, sqs);
    }
}
