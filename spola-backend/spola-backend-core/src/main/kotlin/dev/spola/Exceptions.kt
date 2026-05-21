package dev.spola

import io.ktor.http.HttpStatusCode

open class SpolaException(message: String, cause: Throwable? = null) : Exception(message, cause)

open class ApiException(
    val statusCode: HttpStatusCode,
    message: String,
    cause: Throwable? = null,
) : SpolaException(message, cause)

class NotFoundException(message: String) : ApiException(HttpStatusCode.NotFound, message)

class ValidationException(message: String) : ApiException(HttpStatusCode.BadRequest, message)

class AuthException(message: String) : ApiException(HttpStatusCode.Unauthorized, message)

/**
 * Exception thrown when the agent exceeds the maximum number of turns.
 */
class MaxTurnsExceededException(val maxTurns: Int) :
    SpolaException("Agent exceeded maximum of $maxTurns turns")
