package com.renable.api.distributed.configuration;

import com.amazon.sqs.javamessaging.SQSConnectionFactory;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.renable.api.distributed.ThreadSafeJmsTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.support.converter.MappingJackson2MessageConverter;
import org.springframework.jms.support.converter.MessageType;
import org.springframework.jms.support.destination.CachingDestinationResolver;
import org.springframework.jms.support.destination.DestinationResolver;
import org.springframework.jms.support.destination.DynamicDestinationResolver;

import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Session;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableJms
public class JmsConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(JmsConfiguration.class);

    @Value("${aws.sqs.concurrency:1}")
    private String concurrency;

    @Value("${spring.jms.listener.auto-startup:true}")
    private boolean autoStartup;

    @Autowired
    private SQSConnectionFactory awsSQSConnectionFactory;

    @Autowired
    ConfigurableBeanFactory beanFactory;

    @Bean
    public DefaultJmsListenerContainerFactory jmsListenerContainerFactory() {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(awsSQSConnectionFactory);
        factory.setDestinationResolver(new DestinationCache());
        factory.setMessageConverter(createMessageConverter());
        factory.setConcurrency(concurrency);
        factory.setSessionAcknowledgeMode(Session.CLIENT_ACKNOWLEDGE);
        factory.setErrorHandler(e -> LOG.error("Failed to receive message from JMS", e));
        factory.setAutoStartup(autoStartup);
        return factory;
    }

    @Bean
    public JmsTemplate defaultJmsTemplate() {
        JmsTemplate template = new ThreadSafeJmsTemplate(awsSQSConnectionFactory);
        template.setMessageConverter(createMessageConverter());
        template.setDestinationResolver(new DestinationCache());
        return template;
    }

    private MappingJackson2MessageConverter createMessageConverter() {
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();

        // Store the type of objects in the JSON so they can be properly deserialized
        converter.setObjectMapper(new ObjectMapper()
                .enableDefaultTyping(ObjectMapper.DefaultTyping.JAVA_LANG_OBJECT, JsonTypeInfo.As.PROPERTY));

        // Send text messages with JSON payload
        converter.setTargetType(MessageType.TEXT);

        // A message property with this name will be set to the Java class name, which the message
        // converter uses when deserializing the message again.
        converter.setTypeIdPropertyName("TypeId");

        return converter;
    }

    /**
     * The default destination resolver executes a HTTP request to SQS to fetch the queue URL
     * for every message that is sent. This will cache the destination instead of requesting
     * it again.
     */
    private class DestinationCache implements CachingDestinationResolver {
        private Map<String, Destination> cache = new HashMap<>();
        private DestinationResolver resolver = new DynamicDestinationResolver();

        @Override
        public synchronized Destination resolveDestinationName(Session session, String destinationName, boolean pubSubDomain) throws JMSException {
            // Resolve Spring values in the queue name, e.g. "${some.queue}"
            String resolvedName = beanFactory.resolveEmbeddedValue(destinationName);
            Destination destination = cache.get(resolvedName);

            if (destination == null) {
                destination = resolver.resolveDestinationName(session, resolvedName, pubSubDomain);
                cache.put(resolvedName, destination);
            }

            return destination;
        }

        @Override
        public void removeFromCache(String destinationName) {
            // Resolve Spring values in the queue name, e.g. "${some.queue}"
            String resolvedName = beanFactory.resolveEmbeddedValue(destinationName);
            cache.remove(resolvedName);
        }

        @Override
        public void clearCache() {
            cache.clear();
        }
    }
}
