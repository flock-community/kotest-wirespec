package io.kotest.extensions.wirespec.example.service

import io.kotest.extensions.wirespec.example.domain.CreatePetCommand
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service

@Service
class PetCommandListener(
    private val repository: PetRepository,
) {
    @KafkaListener(topics = ["pets.commands"], groupId = "pet-command-listener")
    fun onCreatePetCommand(command: CreatePetCommand) {
        repository.saveWithId(
            id = command.correlationId,
            name = command.name,
            species = command.species,
        )
    }
}
