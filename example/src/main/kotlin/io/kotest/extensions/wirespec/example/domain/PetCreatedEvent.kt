package io.kotest.extensions.wirespec.example.domain

data class PetCreatedEvent(
    val id: String,
    val name: String,
    val species: String,
)
