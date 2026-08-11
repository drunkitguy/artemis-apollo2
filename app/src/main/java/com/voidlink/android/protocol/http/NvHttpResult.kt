package com.voidlink.android.protocol.http

/**
 * The outcome of an NVHTTP call.
 *
 * Four failure shapes rather than one, because the caller genuinely acts differently on each: a
 * [HostError] carries text worth showing the user, a [TransportError] means "try again or the host
 * is off", a [Malformed] response means we or the host disagree about the protocol, and
 * [NotPaired] means the request was never attempted because HTTPS needs a pin we do not have.
 *
 * Exceptions are deliberately not used for any of these — every one is an expected, ordinary
 * result of talking to a machine on someone's home network.
 */
sealed interface NvHttpResult<out T> {

    /** The host answered with `status_code=200` and a body we understood. */
    class Success<T>(val value: T) : NvHttpResult<T>

    /**
     * A well-formed response reporting a failure (spec §3.2).
     *
     * @property statusCode the `status_code` attribute, or `-1` when absent/unparseable.
     * @property statusMessage the host's own explanation, when supplied.
     */
    class HostError(val statusCode: Int, val statusMessage: String?) : NvHttpResult<Nothing>

    /** The request could not be completed: connect refused, timeout, TLS failure, socket closed. */
    class TransportError(val message: String, val cause: Throwable? = null) : NvHttpResult<Nothing>

    /** The host answered, but the body was not a usable document. */
    class Malformed(val reason: String) : NvHttpResult<Nothing>

    /** An HTTPS endpoint was requested for a host we hold no pinned certificate for (spec §3.1). */
    object NotPaired : NvHttpResult<Nothing>

    /** The value on success, `null` otherwise. */
    fun valueOrNull(): T? = when (this) {
        is Success -> value
        else -> null
    }

    /** True when the call succeeded. */
    val isSuccess: Boolean get() = this is Success<*>

    /** A short human-readable description of the failure, or `null` on success. */
    fun errorDescription(): String? = when (this) {
        is Success<*> -> null
        is HostError -> statusMessage?.takeIf { it.isNotBlank() }
            ?: "host returned status $statusCode"
        is TransportError -> message
        is Malformed -> reason
        NotPaired -> "not paired with this host"
    }
}
