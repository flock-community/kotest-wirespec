package io.kotest.extensions.wirespec.example.domain

data class CreatePetCommand(
    val correlationId: String,
    val name: String,
    val species: String,
)
