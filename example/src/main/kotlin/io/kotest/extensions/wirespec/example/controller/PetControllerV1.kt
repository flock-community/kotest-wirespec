package io.kotest.extensions.wirespec.example.controller

import io.kotest.extensions.wirespec.example.domain.Pet
import io.kotest.extensions.wirespec.example.domain.PetCreatedEvent
import io.kotest.extensions.wirespec.example.service.PetEventPublisher
import io.kotest.extensions.wirespec.example.service.PetRepository
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/pets")
class PetControllerV1(
    private val repository: PetRepository,
    private val publisher: PetEventPublisher,
) {

    data class CreatePetRequest(val name: String, val species: String)
    data class UpdatePetRequest(val name: String? = null, val species: String? = null)
    data class PetResponse(val id: String, val name: String, val species: String, val bornAt: String)
    data class PetPage(val content: List<PetResponse>, val total: Int)
    data class ErrorResponse(val code: String, val message: String)

    @PostMapping
    @ApiResponses(
        ApiResponse(responseCode = "201", content = [Content(schema = Schema(implementation = PetResponse::class))]),
        ApiResponse(responseCode = "400", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    )
    suspend fun createPet(@RequestBody request: CreatePetRequest): ResponseEntity<Any> {
        if (request.name.isBlank() || request.species.isBlank()) {
            return ResponseEntity.badRequest().body(
                ErrorResponse("validation", "name and species must not be blank"),
            )
        }
        val created = repository.create(request.name, request.species)
        publisher.publishPetCreated(
            PetCreatedEvent(id = created.id, name = created.name, species = created.species),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toResponse())
    }

    @GetMapping("/{id}")
    @ApiResponses(
        ApiResponse(responseCode = "200", content = [Content(schema = Schema(implementation = PetResponse::class))]),
        ApiResponse(responseCode = "404", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    )
    suspend fun getPet(@PathVariable id: String): ResponseEntity<Any> {
        val pet = repository.get(id)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ErrorResponse("not_found", "pet $id not found"),
            )
        return ResponseEntity.ok(pet.toResponse())
    }

    @PatchMapping("/{id}")
    @ApiResponses(
        ApiResponse(responseCode = "200", content = [Content(schema = Schema(implementation = PetResponse::class))]),
        ApiResponse(responseCode = "404", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    )
    suspend fun updatePet(
        @PathVariable id: String,
        @RequestBody request: UpdatePetRequest,
    ): ResponseEntity<Any> {
        val updated = repository.update(id, request.name, request.species)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ErrorResponse("not_found", "pet $id not found"),
            )
        return ResponseEntity.ok(updated.toResponse())
    }

    @DeleteMapping("/{id}")
    @ApiResponses(
        ApiResponse(responseCode = "204"),
        ApiResponse(responseCode = "404", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    )
    suspend fun deletePet(@PathVariable id: String): ResponseEntity<Any> =
        if (repository.delete(id)) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse("not_found", "pet $id not found"))
        }

    @GetMapping
    @ApiResponses(
        ApiResponse(responseCode = "200", content = [Content(schema = Schema(implementation = PetPage::class))]),
    )
    suspend fun listPets(
        @RequestParam(defaultValue = "10") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): PetPage {
        val (content, total) = repository.list(limit, offset)
        return PetPage(content = content.map { it.toResponse() }, total = total)
    }

    @PostMapping("/bulk")
    @ApiResponses(
        ApiResponse(responseCode = "201", content = [Content(schema = Schema(implementation = PetPage::class))]),
        ApiResponse(responseCode = "400", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    )
    suspend fun createPetsBulk(@RequestBody requests: List<CreatePetRequest>): ResponseEntity<Any> {
        if (requests.isEmpty()) {
            return ResponseEntity.badRequest().body(
                ErrorResponse("validation", "at least one pet required"),
            )
        }
        val created = requests.map { req ->
            repository.create(req.name.ifBlank { "anon" }, req.species.ifBlank { "unknown" })
                .also {
                    publisher.publishPetCreated(
                        PetCreatedEvent(id = it.id, name = it.name, species = it.species),
                    )
                }
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(
            PetPage(content = created.map { it.toResponse() }, total = created.size),
        )
    }

    private fun Pet.toResponse() = PetResponse(id = id, name = name, species = species, bornAt = bornAt)
}
