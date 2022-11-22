package com.renable.api.distributed;

import com.amazon.sqs.javamessaging.SQSMessageProducer;
import org.springframework.jms.core.JmsTemplate;

import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageProducer;

/**
 * We need to set the message delivery delay individually for each message, but the vanilla JmsTemplate is not
 * threadsafe when it comes to the setDeliveryDelay. So this overrides the setDeliveryDelay and uses a thread-local
 * variable to ensure that different sender threads don't see each others delivery delay.
 */
public class ThreadSafeJmsTemplate extends JmsTemplate {
    private final ThreadLocal<Long> deliveryDelay = ThreadLocal.withInitial(() -> Long.valueOf(-1));

    public ThreadSafeJmsTemplate(ConnectionFactory connectionFactory) {
        super(connectionFactory);
    }

    @Override
    protected void doSend(MessageProducer producer, Message message) throws JMSException {
        long delay = getDeliveryDelay();
        if (delay > 0) {
            ((SQSMessageProducer)producer).setDeliveryDelay(delay);
        }

        super.doSend(producer, message);
    }

    @Override
    public void setDeliveryDelay(long deliveryDelay) {
        this.deliveryDelay.set(deliveryDelay);
    }

    @Override
    public long getDeliveryDelay() {
        return this.deliveryDelay.get();
    }
}
