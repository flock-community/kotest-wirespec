package io.kotest.extensions.spring.wirespec.example.service

import io.kotest.extensions.spring.wirespec.example.domain.Pet
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory, concurrent-safe pet store. Used as the backing for the
 * [io.kotest.extensions.spring.wirespec.example.controller.PetController]; a
 * real app would substitute a reactive Mongo/JPA repository here.
 */
@Component
class PetRepository {

    private val store = ConcurrentHashMap<String, Pet>()

    fun create(name: String, species: String, bornAt: String = "2024-01-01"): Pet {
        val id = UUID.randomUUID().toString()
        val pet = Pet(id = id, name = name, species = species, bornAt = bornAt)
        store[id] = pet
        return pet
    }

    /** Used by the Kafka command listener — caller controls the id (correlationId). */
    fun saveWithId(id: String, name: String, species: String, bornAt: String = "2024-01-01"): Pet {
        val pet = Pet(id = id, name = name, species = species, bornAt = bornAt)
        store[id] = pet
        return pet
    }

    fun get(id: String): Pet? = store[id]

    fun update(id: String, name: String?, species: String?): Pet? {
        val existing = store[id] ?: return null
        val updated = existing.copy(
            name = name ?: existing.name,
            species = species ?: existing.species,
        )
        store[id] = updated
        return updated
    }

    fun delete(id: String): Boolean = store.remove(id) != null

    fun list(limit: Int, offset: Int): Pair<List<Pet>, Int> {
        val all = store.values.toList().sortedBy { it.id }
        val page = all.drop(offset).take(limit)
        return page to all.size
    }
}
