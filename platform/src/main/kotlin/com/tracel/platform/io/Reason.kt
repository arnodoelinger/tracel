package com.tracel.platform.io

/** What [this] said, or its class name when it said nothing: the part of a log line in parentheses. */
public fun Throwable.reason(): String = message ?: javaClass.simpleName
