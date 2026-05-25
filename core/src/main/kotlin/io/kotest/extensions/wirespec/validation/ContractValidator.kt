package io.kotest.extensions.wirespec.validation

import community.flock.wirespec.kotlin.Wirespec

/**
 * Validates a [Wirespec.RawResponse] against an endpoint's contract.
 *
 * Two checks, run in order:
 *
 *   1. **Status code**: the response's HTTP status must match one of the
 *      concrete `ResponseNNN` variants Wirespec emitted for the endpoint
 *      (or, if [expectedStatus] is set, that specific status).
 *   2. **Body schema**: the response body must deserialize into the matched
 *      variant's body type via [Wirespec.Serialization]. Anything that fails
 *      `fromRawResponse` (missing required field, wrong type, …) becomes a
 *      [ContractViolation] with the underlying cause attached.
 *
 * On success the typed response object is returned (already an instance of
 * `ResponseNNN`); callers wanting status narrowing can `is`-check against the
 * expected variant.
 */
/** Kind of contract violation surfaced to test failure messages. */
enum class ContractViolationKind { UnexpectedStatus, UndeclaredStatus, BodyMismatch }

internal class ContractValidator(
    private val endpoint: EndpointReflection,
    private val serialization: Wirespec.Serialization,
) {

    fun validate(
        raw: Wirespec.RawResponse,
        expectedStatuses: Set<Int>? = null,
    ): Any {
        if (expectedStatuses != null && raw.statusCode !in expectedStatuses) {
            throw ContractViolation(
                endpoint = endpoint.endpointName,
                kind = ContractViolationKind.UnexpectedStatus,
                message = "expected status in $expectedStatuses but got ${raw.statusCode}",
                rawResponse = raw,
            )
        }

        val variant = endpoint.responseClassForStatus(raw.statusCode)
            ?: throw ContractViolation(
                endpoint = endpoint.endpointName,
                kind = ContractViolationKind.UndeclaredStatus,
                message = "status ${raw.statusCode} is not declared by the contract. Declared: ${endpoint.responseVariantsByStatus.keys.sorted()}",
                rawResponse = raw,
            )

        val typed = try {
            endpoint.fromRawResponse(serialization, raw)
        } catch (t: Throwable) {
            throw ContractViolation(
                endpoint = endpoint.endpointName,
                kind = ContractViolationKind.BodyMismatch,
                message = "body did not match ${variant.simpleName}: ${t.message ?: t::class.simpleName}",
                rawResponse = raw,
                cause = t,
            )
        }

        return typed
    }
}

class ContractViolation internal constructor(
    val endpoint: String,
    val kind: ContractViolationKind,
    message: String,
    val rawResponse: Wirespec.RawResponse,
    cause: Throwable? = null,
) : AssertionError(
    buildString {
        append("[$endpoint] ")
        append(kind)
        append(": ")
        append(message)
        rawResponse.body?.let { body ->
            append("\n  body=")
            append(String(body).take(2048))
        }
    },
    cause,
)
