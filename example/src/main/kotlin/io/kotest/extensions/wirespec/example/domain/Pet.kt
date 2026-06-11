package io.kotest.extensions.wirespec.example.domain

/**
 * Tiny in-memory pet entity. Fields stay strings to keep the Wirespec contract
 * uncomplicated for the first end-to-end smoke; richer types can come later
 * once the runner is proven.
 */
data class Pet(
    val id: String,
    val name: String,
    val species: String,
    val bornAt: String,
)
