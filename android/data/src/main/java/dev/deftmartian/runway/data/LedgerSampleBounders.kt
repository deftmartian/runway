package dev.deftmartian.runway.data

internal fun List<RouteSampleEntity>.boundRouteSamples(limit: Int): List<RouteSampleEntity> {
    if (size <= limit) return this
    val lastIndex = lastIndex
    return buildList(limit) {
        repeat(limit) { outputIndex ->
            add(this@boundRouteSamples[(outputIndex * lastIndex) / (limit - 1)])
        }
    }
}

internal fun <T> List<T>.boundEvenly(limit: Int): List<T> {
    require(limit > 0) { "Sample limit must be positive." }
    if (size <= limit) return this
    if (limit == 1) return listOf(first())
    return buildList(limit) {
        repeat(limit) { outputIndex ->
            add(this@boundEvenly[(outputIndex * lastIndex) / (limit - 1)])
        }
    }
}
