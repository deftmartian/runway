package dev.deftmartian.runway

import java.net.URI

internal enum class NativeAuthReturnResult {
    Approved,
    Denied,
}

/**
 * The app callback is only a wake-up signal. The ViewModel still exchanges the previously stored,
 * origin-bound device request with the server before accepting a session.
 */
internal fun parseNativeAuthReturn(value: String?): NativeAuthReturnResult? {
    if (value.isNullOrBlank() || value.length > 200) return null
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (
        uri.scheme != "runway-native" ||
        uri.host != "auth" ||
        uri.port != -1 ||
        uri.userInfo != null ||
        (uri.rawPath.orEmpty().isNotEmpty() && uri.rawPath != "/") ||
        uri.rawFragment != null
    ) {
        return null
    }
    return when (uri.rawQuery) {
        "result=approved" -> NativeAuthReturnResult.Approved
        "result=denied" -> NativeAuthReturnResult.Denied
        else -> null
    }
}
