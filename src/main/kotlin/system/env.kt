package system

import arrow.core.None
import arrow.core.Option
import arrow.core.Some

/**
 * Read a variable from the system environment
 */
fun env(name: String): Option<String> = Option.fromNullable(System.getenv(name))
