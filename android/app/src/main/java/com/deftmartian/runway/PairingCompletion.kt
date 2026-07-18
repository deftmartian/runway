package com.deftmartian.runway

internal sealed interface PairingCompletion {
    data class Connected(val credential: AndroidCredential) : PairingCompletion
    data object Invalid : PairingCompletion
    data object Retryable : PairingCompletion
    data object StorageFailed : PairingCompletion
}

/**
 * Persists the bearer credential before an Activity is asked to render the result. Pairing codes
 * are single-use, so credential durability cannot depend on a lifecycle-gated UI callback running.
 */
internal fun completePairing(
    result: PairingApiResult,
    saveCredential: (AndroidCredential) -> Boolean,
): PairingCompletion = when (result) {
    is PairingApiResult.Paired -> {
        if (saveCredential(result.credential)) {
            PairingCompletion.Connected(result.credential)
        } else {
            PairingCompletion.StorageFailed
        }
    }
    PairingApiResult.Invalid -> PairingCompletion.Invalid
    PairingApiResult.Retryable -> PairingCompletion.Retryable
}
