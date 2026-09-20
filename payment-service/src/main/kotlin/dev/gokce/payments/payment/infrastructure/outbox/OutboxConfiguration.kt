package dev.gokce.payments.payment.infrastructure.outbox

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties::class)
class OutboxConfiguration {

    /**
     * Declared explicitly because auto-creation is switched off on the broker: a typo in a topic name
     * should fail at startup, not quietly create a second topic nobody is listening to.
     */
    @Bean
    fun paymentEventsTopic(properties: OutboxProperties): NewTopic = TopicBuilder
        .name(properties.topic)
        .partitions(properties.topicPartitions)
        .replicas(properties.topicReplicas)
        .build()
}
