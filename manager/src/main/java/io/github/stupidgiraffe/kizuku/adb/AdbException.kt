// Adapted from d4rken-org/porter a288febb4e42 (Apache-2.0); see NOTICE.
package io.github.stupidgiraffe.kizuku.adb

@Suppress("NOTHING_TO_INLINE")
inline fun adbError(message: Any): Nothing = throw AdbException(message.toString())

open class AdbException : Exception {

    constructor(message: String, cause: Throwable?) : super(message, cause)
    constructor(message: String) : super(message)
    constructor(cause: Throwable) : super(cause)
    constructor()
}

class AdbInvalidPairingCodeException : AdbException()

class AdbKeyException(cause: Throwable) : AdbException(cause)

/** Wireless debugging refused this app's key: it has not been paired on this device, or no longer is. */
class AdbPairingRequiredException(cause: Throwable) : AdbException(cause)
