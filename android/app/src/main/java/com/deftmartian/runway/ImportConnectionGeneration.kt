package com.deftmartian.runway

internal data class ImportConnectionGeneration(
    val deviceId: String,
    val token: String,
    val treeUri: String,
    val treeGeneration: Long,
) {
    fun isCurrent(credential: AndroidCredential?, treeState: TreeAccessState): Boolean {
        val connected = treeState as? TreeAccessState.Connected
        return matches(
            deviceId = credential?.deviceId,
            token = credential?.token,
            treeUri = connected?.uri?.toString(),
            treeGeneration = connected?.generation,
        )
    }

    fun matches(
        deviceId: String?,
        token: String?,
        treeUri: String?,
        treeGeneration: Long?,
    ): Boolean =
        deviceId == this.deviceId &&
            token == this.token &&
            treeUri == this.treeUri &&
            treeGeneration == this.treeGeneration

    companion object {
        fun capture(
            credential: AndroidCredential,
            treeState: TreeAccessState.Connected,
        ): ImportConnectionGeneration = ImportConnectionGeneration(
            deviceId = credential.deviceId,
            token = credential.token,
            treeUri = treeState.uri.toString(),
            treeGeneration = treeState.generation,
        )
    }
}
