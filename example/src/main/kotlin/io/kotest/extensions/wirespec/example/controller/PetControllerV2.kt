package io.kotest.extensions.wirespec.example.controller

import io.kotest.extensions.wirespec.example.service.PetRepository
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * v2 of the pet API. A deliberately slimmed-down surface: a single read
 * endpoint that exposes only the fields a v2 consumer needs (id and name),
 * dropping species/bornAt from the v1 [PetControllerV1.PetResponse].
 */
@RestController
@RequestMapping("/api/v2/pets")
class PetControllerV2(
    private val repository: PetRepository,
) {

    data class PetV2Response(val id: String, val name: String)
    data class ErrorResponse(val code: String, val message: String)

    // Deliberately named getPet (same as PetControllerV1.getPet). The two
    // controllers share an operation name across versions; the Wirespec
    // extractor disambiguates by suffixing — v1 -> GetPet1, v2 -> GetPet2.
    @GetMapping("/{id}")
    @ApiResponses(
        ApiResponse(responseCode = "200", content = [Content(schema = Schema(implementation = PetV2Response::class))]),
        ApiResponse(responseCode = "404", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    )
    suspend fun getPet(@PathVariable id: String): ResponseEntity<Any> {
        val pet = repository.get(id)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ErrorResponse("not_found", "pet $id not found"),
            )
        return ResponseEntity.ok(PetV2Response(id = pet.id, name = pet.name))
    }
}
