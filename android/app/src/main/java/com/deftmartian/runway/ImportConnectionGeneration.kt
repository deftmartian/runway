package com.deftmartian.runway

internal data class ImportConnectionGeneration(
    val origin: String,
    val serverGeneration: Long,
    val deviceId: String,
    val token: String,
    val treeUri: String,
    val treeGeneration: Long,
) {
    fun isCurrent(
        serverConnection: ServerConnection?,
        credential: AndroidCredential?,
        treeState: TreeAccessState,
    ): Boolean {
        val connected = treeState as? TreeAccessState.Connected
        return matches(
            origin = serverConnection?.origin,
            serverGeneration = serverConnection?.generation,
            credentialOrigin = credential?.origin,
            deviceId = credential?.deviceId,
            token = credential?.token,
            treeUri = connected?.uri?.toString(),
            treeGeneration = connected?.generation,
        )
    }

    fun matches(
        origin: String?,
        serverGeneration: Long?,
        credentialOrigin: String?,
        deviceId: String?,
        token: String?,
        treeUri: String?,
        treeGeneration: Long?,
    ): Boolean =
        origin == this.origin &&
            serverGeneration == this.serverGeneration &&
            credentialOrigin == this.origin &&
            deviceId == this.deviceId &&
            token == this.token &&
            treeUri == this.treeUri &&
            treeGeneration == this.treeGeneration

    companion object {
        fun capture(
            serverConnection: ServerConnection,
            credential: AndroidCredential,
            treeState: TreeAccessState.Connected,
        ): ImportConnectionGeneration = ImportConnectionGeneration(
            origin = serverConnection.origin,
            serverGeneration = serverConnection.generation,
            deviceId = credential.deviceId,
            token = credential.token,
            treeUri = treeState.uri.toString(),
            treeGeneration = treeState.generation,
        )
    }

}
