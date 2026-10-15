package com.evnsolution.ups.partner

/** A failure while talking to Ours24. The message never holds the key or data from Ours24. */
class Ours24Exception(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal fun fail(message: String, cause: Throwable? = null): Nothing = throw Ours24Exception(message, cause)
