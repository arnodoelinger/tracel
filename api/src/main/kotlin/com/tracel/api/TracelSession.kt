package com.tracel.api

/**
 * Owner of what you start through the API. Close it, and everything it owns is closed with it.
 *
 * Example:
 * ```
 * Tracel.session("anti-grief") {
 *     own(myWatcher)
 * }   // Closed here
 * ```
 */
@Unstable
public interface TracelSession : AutoCloseable {
    /** Name shown in diagnostics. */
    public val name: String

    /** `true` after [close]. */
    public val isClosed: Boolean

    /**
     * Makes [resource] close together with this session, last in first out, and returns it.
     *
     * @return [resource]
     */
    public fun <T : AutoCloseable> own(resource: T): T

    /**
     * Ends everything this session owns.
     *
     * Safe to call twice.
     */
    override fun close()
}
