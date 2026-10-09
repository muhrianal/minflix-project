package com.minflix.worker;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** No profile: both the api (publisher) and the worker (consumer) declare the queue. */
@Configuration
public class QueueConfig {

    public static final String TRANSCODE_QUEUE = "transcode";

    @Bean
    Queue transcodeQueue() {
        return new Queue(TRANSCODE_QUEUE, true);
    }

    @Bean
    MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
