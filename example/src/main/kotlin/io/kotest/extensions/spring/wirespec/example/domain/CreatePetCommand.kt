package io.kotest.extensions.spring.wirespec.example.domain

data class CreatePetCommand(
    val correlationId: String,
    val name: String,
    val species: String,
)
