package dev.deftmartian.runway

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

internal enum class NativeDestination(
    val label: String,
    val view: String,
    val iconRes: Int,
) {
    Setup("Set up", "onboarding", R.drawable.ic_nav_today),
    Today("Today", "calendar", R.drawable.ic_nav_today),
    Calendar("Calendar", "calendar", R.drawable.ic_nav_calendar),
    Review("Review", "review", R.drawable.ic_nav_review),
    Progress("Progress", "stats", R.drawable.ic_nav_progress),
    Settings("Settings", "settings", R.drawable.ic_nav_settings),
}

internal data class NativeNotice(val message: String, val isError: Boolean = false)
internal data class NativeActionPreview(
    val applyAction: String,
    val payload: JSONObject,
    val preview: JSONObject,
)

internal sealed interface RunwayUiState {
    data object Loading : RunwayUiState

    data class SignedOut(
        val pending: PendingMobileAuthorization? = null,
        val starting: Boolean = false,
        val message: String? = null,
    ) : RunwayUiState

    data class Ready(
        val bootstrap: JSONObject,
        val destination: NativeDestination,
        val payload: JSONObject?,
        val loading: Boolean,
        val actionPending: Boolean = false,
        val notice: NativeNotice? = null,
        val actionPreview: NativeActionPreview? = null,
        val completedAction: String? = null,
    ) : RunwayUiState

    data class Failed(val message: String) : RunwayUiState
}

internal class RunwayViewModel(application: Application) : AndroidViewModel(application) {
    private val origin = requireNotNull(ServerConnectionStore(application).currentOrigin())
    private val api = MobileApiClient(origin)
    private val sessionStore = MobileSessionStore(application, origin)
    private val mutableState = MutableStateFlow<RunwayUiState>(RunwayUiState.Loading)
    private val viewLoadGate = ViewLoadRequestGate()
    private var session: MobileSession? = null
    private var authorizationJob: Job? = null
    private var currentQuery = ""

    val state: StateFlow<RunwayUiState> = mutableState.asStateFlow()

    init {
        restore()
    }

    fun startAuthorization() {
        if (authorizationJob?.isActive == true) return
        authorizationJob = viewModelScope.launch {
            mutableState.value = RunwayUiState.SignedOut(starting = true)
            when (val result = withContext(Dispatchers.IO) { api.beginAuthorization() }) {
                is MobileAuthorizationStartResult.Started -> {
                    if (!sessionStore.savePending(result.pending)) {
                        mutableState.value = RunwayUiState.SignedOut(
                            message = "Android could not protect the sign-in request on this device.",
                        )
                        return@launch
                    }
                    mutableState.value = RunwayUiState.SignedOut(pending = result.pending)
                    poll(result.pending)
                }
                MobileAuthorizationStartResult.Rejected -> {
                    mutableState.value = RunwayUiState.SignedOut(
                        message = "This server did not accept native sign-in.",
                    )
                }
                MobileAuthorizationStartResult.Retryable -> {
                    mutableState.value = RunwayUiState.SignedOut(
                        message = "The server could not be reached. Try again.",
                    )
                }
            }
        }
    }

    fun cancelAuthorization() {
        authorizationJob?.cancel()
        authorizationJob = null
        sessionStore.clearPending()
        mutableState.value = RunwayUiState.SignedOut()
    }

    fun selectDestination(destination: NativeDestination) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (ready.destination == destination && ready.payload != null) return
        currentQuery = ""
        mutableState.value = ready.copy(
            destination = destination,
            payload = null,
            loading = true,
            notice = null,
        )
        loadView(destination, "")
    }

    fun loadCalendarMonth(month: String) {
        if (!month.matches(Regex("\\d{4}-\\d{2}"))) return
        currentQuery = "?month=$month"
        loadView(NativeDestination.Calendar, currentQuery)
    }

    fun refresh() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        loadView(ready.destination, currentQuery)
    }

    fun submitAction(action: String, payload: JSONObject) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (ready.actionPending) return
        val currentSession = session ?: return expireSession()
        val requestId = UUID.randomUUID()
        viewLoadGate.invalidate()
        mutableState.value = ready.copy(
            actionPending = true,
            notice = null,
            actionPreview = if (action in WORKOUT_PREVIEW_APPLY_ACTIONS) {
                ready.actionPreview
            } else {
                null
            },
            completedAction = null,
        )
        viewModelScope.launch {
            var result = withContext(Dispatchers.IO) {
                api.runAction(currentSession, action, payload, requestId)
            }
            if (result == MobileActionResult.Retryable) {
                delay(750)
                result = withContext(Dispatchers.IO) {
                    api.runAction(currentSession, action, payload, requestId)
                }
            }
            when (result) {
                is MobileActionResult.Completed -> {
                    val notice = NativeNotice(
                        result.payload.optString("message").ifBlank { "Change saved." },
                    )
                    val preview = result.payload.optJSONObject("preview")
                    if (action.startsWith("preview_") && preview != null) {
                        val latest = mutableState.value as? RunwayUiState.Ready ?: return@launch
                        mutableState.value = latest.copy(
                            actionPending = false,
                            notice = notice,
                            completedAction = action,
                            actionPreview = NativeActionPreview(
                                applyAction = when (action) {
                                    "preview_workout_edit" -> "apply_workout_edit"
                                    "preview_workout_add" -> "apply_workout_add"
                                    "preview_workout_removal" -> "remove_workout"
                                    else -> return@launch showActionFailure(
                                        "This preview cannot be applied by this app.",
                                    )
                                },
                                payload = JSONObject(payload.toString()),
                                preview = preview,
                            ),
                        )
                    } else if (action == "create_plan") {
                        loadBootstrap(notice)
                    } else {
                        val latest = mutableState.value as? RunwayUiState.Ready ?: return@launch
                        mutableState.value = latest.copy(
                            actionPending = false,
                            completedAction = action,
                            actionPreview = null,
                        )
                        loadView(latest.destination, currentQuery, notice)
                    }
                }
                MobileActionResult.Unauthorized -> expireSession()
                MobileActionResult.Incompatible -> {
                    showActionFailure("This change needs a newer runway server.")
                }
                is MobileActionResult.Rejected -> showActionFailure(result.message)
                is MobileActionResult.Uncertain -> {
                    val latest = mutableState.value as? RunwayUiState.Ready ?: return@launch
                    mutableState.value = latest.copy(
                        actionPending = false,
                        actionPreview = null,
                        completedAction = action,
                    )
                    loadView(
                        latest.destination,
                        currentQuery,
                        NativeNotice(result.message, isError = true),
                    )
                }
                MobileActionResult.Retryable -> {
                    val latest = mutableState.value as? RunwayUiState.Ready ?: return@launch
                    mutableState.value = latest.copy(
                        actionPending = false,
                        actionPreview = null,
                        completedAction = action,
                    )
                    loadView(
                        latest.destination,
                        currentQuery,
                        NativeNotice(
                            "The server could not confirm that change. Review the refreshed screen before trying again.",
                            isError = true,
                        ),
                    )
                }
            }
        }
    }

    fun confirmActionPreview() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        val preview = ready.actionPreview ?: return
        val payload = JSONObject(preview.payload.toString()).put("confirmRisk", true)
        submitAction(preview.applyAction, payload)
    }

    fun dismissActionPreview() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        mutableState.value = ready.copy(actionPreview = null, notice = null)
    }

    fun retry() {
        when (val current = mutableState.value) {
            RunwayUiState.Loading -> Unit
            is RunwayUiState.SignedOut -> {
                if (current.pending == null && !current.starting) startAuthorization()
            }
            is RunwayUiState.Ready -> refresh()
            is RunwayUiState.Failed -> {
                if (session == null) {
                    mutableState.value = RunwayUiState.SignedOut()
                } else {
                    loadBootstrap()
                }
            }
        }
    }

    fun signOut() {
        authorizationJob?.cancel()
        viewLoadGate.invalidate()
        val currentSession = session
        sessionStore.clear()
        session = null
        mutableState.value = RunwayUiState.SignedOut()
        if (currentSession != null) {
            viewModelScope.launch(Dispatchers.IO) { api.signOut(currentSession) }
        }
    }

    private fun restore() {
        val savedSession = sessionStore.loadSession()
        if (savedSession != null) {
            session = savedSession
            loadBootstrap()
            return
        }
        val pending = sessionStore.loadPending()
        if (pending != null) {
            mutableState.value = RunwayUiState.SignedOut(pending = pending)
            authorizationJob = viewModelScope.launch { poll(pending) }
        } else {
            mutableState.value = RunwayUiState.SignedOut()
        }
    }

    private suspend fun poll(pending: PendingMobileAuthorization) {
        var intervalSeconds = pending.pollIntervalSeconds
        while (!pending.isExpired()) {
            delay(intervalSeconds * 1_000L)
            when (val result = withContext(Dispatchers.IO) { api.pollAuthorization(pending) }) {
                is MobileAuthorizationPollResult.Authorized -> {
                    if (!sessionStore.saveSession(result.session)) {
                        mutableState.value = RunwayUiState.SignedOut(
                            message = "Android could not protect the signed-in session.",
                        )
                        return
                    }
                    session = result.session
                    sessionStore.clearPending()
                    loadBootstrap()
                    return
                }
                MobileAuthorizationPollResult.Pending -> Unit
                is MobileAuthorizationPollResult.SlowDown -> {
                    intervalSeconds = (intervalSeconds + result.extraSeconds).coerceAtMost(60)
                }
                MobileAuthorizationPollResult.Denied -> {
                    sessionStore.clearPending()
                    mutableState.value = RunwayUiState.SignedOut(
                        message = "Sign-in was denied in the browser.",
                    )
                    return
                }
                MobileAuthorizationPollResult.Expired -> {
                    sessionStore.clearPending()
                    mutableState.value = RunwayUiState.SignedOut(
                        message = "That sign-in request expired. Start again.",
                    )
                    return
                }
                MobileAuthorizationPollResult.Retryable -> {
                    intervalSeconds = maxOf(intervalSeconds, 10)
                    val current = mutableState.value as? RunwayUiState.SignedOut
                    mutableState.value = RunwayUiState.SignedOut(
                        pending = pending,
                        message = current?.message
                            ?: "Waiting for the server. Sign-in will keep checking.",
                    )
                }
            }
        }
        sessionStore.clearPending()
        mutableState.value = RunwayUiState.SignedOut(
            message = "That sign-in request expired. Start again.",
        )
    }

    private fun loadBootstrap(notice: NativeNotice? = null) {
        viewLoadGate.invalidate()
        viewModelScope.launch {
            mutableState.value = RunwayUiState.Loading
            val currentSession = session ?: run {
                mutableState.value = RunwayUiState.SignedOut()
                return@launch
            }
            when (
                val result = withContext(Dispatchers.IO) {
                    api.getView(currentSession, "bootstrap")
                }
            ) {
                is MobileViewResult.Loaded -> {
                    val setupComplete = result.payload.optBoolean("setupComplete", false)
                    val destination = if (setupComplete) {
                        NativeDestination.Today
                    } else {
                        NativeDestination.Setup
                    }
                    mutableState.value = RunwayUiState.Ready(
                        bootstrap = result.payload,
                        destination = destination,
                        payload = null,
                        loading = true,
                        notice = notice,
                    )
                    loadView(destination, "", notice)
                }
                MobileViewResult.Unauthorized -> expireSession()
                MobileViewResult.Incompatible -> {
                    mutableState.value = RunwayUiState.Failed(
                        "This app and server do not support the same native API version.",
                    )
                }
                MobileViewResult.Retryable -> {
                    mutableState.value = RunwayUiState.Failed(
                        "Runway could not load from $origin.",
                    )
                }
            }
        }
    }

    private fun loadView(
        destination: NativeDestination,
        query: String,
        notice: NativeNotice? = null,
    ) {
        val request = viewLoadGate.begin(destination.name, query)
        viewModelScope.launch {
            val ready = mutableState.value as? RunwayUiState.Ready ?: return@launch
            val currentSession = session ?: return@launch expireSession()
            mutableState.value = ready.copy(
                destination = destination,
                loading = true,
                notice = notice,
            )
            when (
                val result = withContext(Dispatchers.IO) {
                    api.getView(currentSession, destination.view, query)
                }
            ) {
                is MobileViewResult.Loaded -> {
                    val latest = mutableState.value as? RunwayUiState.Ready ?: return@launch
                    if (
                        viewLoadGate.isCurrent(
                            request,
                            latest.destination.name,
                            currentQuery,
                        )
                    ) {
                        mutableState.value = latest.copy(
                            payload = result.payload,
                            loading = false,
                            actionPending = false,
                            notice = notice,
                        )
                    }
                }
                MobileViewResult.Unauthorized -> expireSession()
                MobileViewResult.Incompatible -> {
                    val latest = mutableState.value as? RunwayUiState.Ready ?: return@launch
                    if (
                        !viewLoadGate.isCurrent(
                            request,
                            latest.destination.name,
                            currentQuery,
                        )
                    ) {
                        return@launch
                    }
                    mutableState.value = latest.copy(
                        loading = false,
                        actionPending = false,
                        notice = NativeNotice(
                            "This screen needs a newer runway server.",
                            isError = true,
                        ),
                    )
                }
                MobileViewResult.Retryable -> {
                    val latest = mutableState.value as? RunwayUiState.Ready ?: return@launch
                    if (
                        !viewLoadGate.isCurrent(
                            request,
                            latest.destination.name,
                            currentQuery,
                        )
                    ) {
                        return@launch
                    }
                    mutableState.value = latest.copy(
                        loading = false,
                        actionPending = false,
                        notice = NativeNotice(
                            "Could not refresh. Check the server connection and try again.",
                            isError = true,
                        ),
                    )
                }
            }
        }
    }

    private fun expireSession() {
        viewLoadGate.invalidate()
        sessionStore.clear()
        session = null
        mutableState.value = RunwayUiState.SignedOut(
            message = "Your session ended. Sign in again.",
        )
    }

    private fun showActionFailure(message: String) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        mutableState.value = ready.copy(
            actionPending = false,
            notice = NativeNotice(message, isError = true),
        )
    }

    private companion object {
        val WORKOUT_PREVIEW_APPLY_ACTIONS = setOf(
            "apply_workout_edit",
            "apply_workout_add",
            "remove_workout",
        )
    }
}
