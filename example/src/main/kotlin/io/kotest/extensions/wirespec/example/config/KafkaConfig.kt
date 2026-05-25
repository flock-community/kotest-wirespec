package io.kotest.extensions.spring.wirespec.example.config

import io.kotest.extensions.spring.wirespec.example.domain.PetCreatedEvent
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory

@Configuration
class KafkaConfig {

    @Bean
    fun petCreatedProducerFactory(props: KafkaProperties): ProducerFactory<String, PetCreatedEvent> =
        DefaultKafkaProducerFactory(props.buildProducerProperties(null))

    @Bean
    fun petCreatedKafkaTemplate(
        factory: ProducerFactory<String, PetCreatedEvent>,
    ): KafkaTemplate<String, PetCreatedEvent> = KafkaTemplate(factory)
}
