package example

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

@RestController
@RequestMapping("/api/pets")
class PetController {

    data class PetResponse(val id: String, val name: String)
    data class ErrorResponse(val code: String, val message: String)

    @GetMapping("/{id}")
    @ApiResponses(
        ApiResponse(responseCode = "200", content = [Content(schema = Schema(implementation = PetResponse::class))]),
        ApiResponse(responseCode = "404", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    )
    fun getPet(@PathVariable id: String): ResponseEntity<Any> =
        if (id == "missing") {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse("not_found", "pet $id not found"))
        } else {
            ResponseEntity.ok(PetResponse(id = id, name = "Rex"))
        }
}
