package dev.deftmartian.runway

internal data class ViewLoadRequest(
    val generation: Long,
    val destination: String,
    val query: String,
)

/**
 * Network calls can finish out of order. A response may update the UI only while it still
 * represents the latest destination and query requested by the runner.
 */
internal class ViewLoadRequestGate {
    private var generation = 0L

    fun begin(destination: String, query: String): ViewLoadRequest =
        ViewLoadRequest(++generation, destination, query)

    fun invalidate() {
        generation += 1
    }

    fun isCurrent(
        request: ViewLoadRequest,
        destination: String,
        query: String,
    ): Boolean =
        request.generation == generation &&
            request.destination == destination &&
            request.query == query
}
