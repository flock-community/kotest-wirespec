package io.kotest.extensions.spring.wirespec.example.domain

data class PetCreatedEvent(
    val id: String,
    val name: String,
    val species: String,
)
