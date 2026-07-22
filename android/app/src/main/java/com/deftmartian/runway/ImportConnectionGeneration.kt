package com.deftmartian.runway

internal data class ImportConnectionGeneration(
    val origin: String,
    val serverGeneration: Long,
    val deviceId: String,
    val token: String,
    val credentialGeneration: Long,
    val treeUri: String,
    val treeGeneration: Long,
) {
    fun isCurrent(
        serverConnection: ServerConnection?,
        credentialState: AndroidCredentialState,
        treeState: TreeAccessState,
    ): Boolean {
        val credential = credentialState.credential
        val connected = treeState as? TreeAccessState.Connected
        return matches(
            origin = serverConnection?.origin,
            serverGeneration = serverConnection?.generation,
            credentialOrigin = credential?.origin,
            deviceId = credential?.deviceId,
            token = credential?.token,
            credentialGeneration = credentialState.generation,
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
        credentialGeneration: Long?,
        treeUri: String?,
        treeGeneration: Long?,
    ): Boolean =
        origin == this.origin &&
            serverGeneration == this.serverGeneration &&
            credentialOrigin == this.origin &&
            deviceId == this.deviceId &&
            token == this.token &&
            credentialGeneration == this.credentialGeneration &&
            treeUri == this.treeUri &&
            treeGeneration == this.treeGeneration

    companion object {
        fun capture(
            serverConnection: ServerConnection,
            credentialState: AndroidCredentialState,
            treeState: TreeAccessState.Connected,
        ): ImportConnectionGeneration {
            val credential = requireNotNull(credentialState.credential)
            return ImportConnectionGeneration(
                origin = serverConnection.origin,
                serverGeneration = serverConnection.generation,
                deviceId = credential.deviceId,
                token = credential.token,
                credentialGeneration = credentialState.generation,
                treeUri = treeState.uri.toString(),
                treeGeneration = treeState.generation,
            )
        }
    }

}
