package io.kotest.extensions.wirespec.example.service

import io.kotest.extensions.wirespec.example.domain.PetCreatedEvent
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service

@Service
class PetEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, PetCreatedEvent>,
) {
    fun publishPetCreated(event: PetCreatedEvent) {
        kafkaTemplate.send("pets.events", event.id, event)
    }
}
