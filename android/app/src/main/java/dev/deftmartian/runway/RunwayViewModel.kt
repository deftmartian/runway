package dev.deftmartian.runway

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
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
import java.nio.charset.StandardCharsets
import java.util.UUID

internal enum class NativeDestination(
    val label: String,
    val view: String,
    val iconRes: Int,
    val primaryNavigation: Boolean = true,
    val navigationParent: NativeDestination? = null,
) {
    Setup(
        "Set up",
        "onboarding",
        R.drawable.ic_nav_calendar,
        primaryNavigation = false,
    ),
    Calendar("Calendar", "calendar", R.drawable.ic_nav_calendar),
    Inbox("Inbox", "review", R.drawable.ic_nav_inbox),
    Stats("Stats", "stats", R.drawable.ic_nav_stats),
    History("History", "history", R.drawable.ic_nav_history),
    Settings("Settings", "settings", R.drawable.ic_nav_settings),
    AccountSecurity(
        "Account security",
        "account-security",
        R.drawable.ic_nav_settings,
        primaryNavigation = false,
        navigationParent = Settings,
    ),
    HistoryDetail(
        "Plan record",
        "history-detail",
        R.drawable.ic_nav_history,
        primaryNavigation = false,
        navigationParent = History,
    ),
}

internal fun NativeDestination.primaryNavigationDestination(): NativeDestination? =
    generateSequence(this) { destination -> destination.navigationParent }
        .firstOrNull(NativeDestination::primaryNavigation)

internal data class NativeNotice(val message: String, val isError: Boolean = false)
internal data class NativeActionPreview(
    val command: PreviewableMobileCommand,
    val preview: NativeActionPreviewDto,
)

internal data class NativeAccountSecurityEphemeral(
    val setupPending: Boolean = false,
    val totpSetup: NativeTotpSetup? = null,
    val recoveryCodes: List<String> = emptyList(),
) {
    override fun toString(): String =
        "NativeAccountSecurityEphemeral(" +
            "setupPending=$setupPending, totpSetup=<redacted>, recoveryCodes=<redacted>)"
}

internal fun NativeAccountSecurityEphemeral.clearSensitiveMaterialForBackground() = copy(
    totpSetup = null,
    recoveryCodes = emptyList(),
)

internal fun installReplacementSession(
    current: MobileSession,
    replacement: MobileSession,
    persist: (MobileSession) -> Boolean,
): MobileSession? {
    if (
        current.origin != replacement.origin ||
        current.token == replacement.token ||
        replacement.isExpired()
    ) {
        return null
    }
    return if (runCatching { persist(replacement) }.getOrDefault(false)) replacement else null
}

internal fun mergeNativeHistoryPayloads(
    previous: NativeHistoryPayload?,
    next: NativeHistoryPayload?,
): NativeHistoryPayload? {
    if (previous == null || next == null) return null
    val previousHistory = previous.history ?: return next
    val nextHistory = next.history ?: return previous
    val mergedItems = (previousHistory.items + nextHistory.items)
        .distinctBy { item ->
            item.plan?.id
                ?: listOf(
                    item.plan?.startDate,
                    item.plan?.targetDate,
                    item.goal?.title,
                ).joinToString("|")
        }
    return previous.copy(
        history = nextHistory.copy(items = mergedItems),
        offset = next.offset,
        pageSize = next.pageSize,
    )
}

internal sealed interface RunwayUiState {
    data object Loading : RunwayUiState

    data class SignedOut(
        val capabilities: NativeAuthCapabilities? = null,
        val pending: PendingMobileAuthorization? = null,
        val starting: Boolean = false,
        val signingIn: Boolean = false,
        val challenge: NativeAuthChallenge? = null,
        val selectedSecondFactor: NativeSecondFactor = NativeSecondFactor.Totp,
        val message: String? = null,
    ) : RunwayUiState

    data class Ready(
        val bootstrap: NativeBootstrapPayload,
        val destination: NativeDestination,
        val payload: NativeViewPayload?,
        val loading: Boolean,
        val actionPending: Boolean = false,
        val notice: NativeNotice? = null,
        val actionPreview: NativeActionPreview? = null,
        val completedAction: String? = null,
        val activityEvidence: Map<String, NativeActivityEvidence> = emptyMap(),
        val activityEvidenceLoading: Set<String> = emptySet(),
        val activityEvidenceFailures: Set<String> = emptySet(),
        val accountSecurityEphemeral: NativeAccountSecurityEphemeral =
            NativeAccountSecurityEphemeral(),
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
        val previous = mutableState.value as? RunwayUiState.SignedOut ?: return
        authorizationJob = viewModelScope.launch {
            mutableState.value = previous.copy(
                pending = null,
                starting = true,
                signingIn = false,
                challenge = null,
                message = null,
            )
            when (val result = withContext(Dispatchers.IO) { api.beginAuthorization() }) {
                is MobileAuthorizationStartResult.Started -> {
                    if (!sessionStore.savePending(result.pending)) {
                        mutableState.value = previous.copy(
                            message = "Android could not protect the sign-in request on this device.",
                        )
                        return@launch
                    }
                    mutableState.value = previous.copy(pending = result.pending)
                    poll(result.pending)
                }
                MobileAuthorizationStartResult.Rejected -> {
                    mutableState.value = previous.copy(
                        message = "This server did not accept native sign-in.",
                    )
                }
                MobileAuthorizationStartResult.Retryable -> {
                    mutableState.value = previous.copy(
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
        val current = mutableState.value as? RunwayUiState.SignedOut
        mutableState.value = RunwayUiState.SignedOut(capabilities = current?.capabilities)
    }

    fun authorizationReturned(approved: Boolean) {
        val current = mutableState.value as? RunwayUiState.SignedOut ?: return
        val pending = current.pending ?: return
        if (!approved) {
            cancelAuthorization()
            return
        }
        authorizationJob?.cancel()
        authorizationJob = viewModelScope.launch { poll(pending, waitBeforeFirstPoll = false) }
    }

    fun signInLocal(email: String, password: String) {
        val current = mutableState.value as? RunwayUiState.SignedOut ?: return
        if (current.signingIn || current.capabilities?.local != true) return
        mutableState.value = current.copy(signingIn = true, challenge = null, message = null)
        viewModelScope.launch {
            when (
                val result = withContext(Dispatchers.IO) {
                    api.signInLocal(email, password)
                }
            ) {
                is NativeLocalSignInResult.Authorized -> acceptSession(result.session)
                is NativeLocalSignInResult.TwoFactorRequired -> {
                    val latest = mutableState.value as? RunwayUiState.SignedOut ?: return@launch
                    mutableState.value = latest.copy(
                        signingIn = false,
                        challenge = result.challenge,
                        selectedSecondFactor = if (
                            NativeSecondFactor.Totp in result.challenge.methods
                        ) {
                            NativeSecondFactor.Totp
                        } else {
                            NativeSecondFactor.BackupCode
                        },
                    )
                }
                NativeLocalSignInResult.InvalidCredentials -> showSignInFailure(
                    "Email or password is not correct.",
                )
                is NativeLocalSignInResult.RateLimited -> showSignInFailure(
                    result.retryAfterSeconds?.let {
                        "Too many sign-in attempts. Try again in ${it}s."
                    } ?: "Too many sign-in attempts. Try again later.",
                )
                NativeLocalSignInResult.Unavailable -> showSignInFailure(
                    "Local sign-in is not enabled on this server.",
                )
                NativeLocalSignInResult.Retryable -> showSignInFailure(
                    "The server could not be reached. Try again.",
                )
            }
        }
    }

    fun signUpLocal(name: String, email: String, password: String) {
        val current = mutableState.value as? RunwayUiState.SignedOut ?: return
        if (current.signingIn || current.capabilities?.localSignups != true) return
        mutableState.value = current.copy(signingIn = true, challenge = null, message = null)
        viewModelScope.launch {
            when (
                val result = withContext(Dispatchers.IO) {
                    api.signUpLocal(name, email, password)
                }
            ) {
                is NativeLocalSignUpResult.Authorized -> acceptSession(result.session)
                NativeLocalSignUpResult.Rejected -> showSignInFailure(
                    "Account could not be created. Check the name, email, and 12-character password.",
                )
                is NativeLocalSignUpResult.RateLimited -> showSignInFailure(
                    result.retryAfterSeconds?.let {
                        "Too many account-creation attempts. Try again in ${it}s."
                    } ?: "Too many account-creation attempts. Try again later.",
                )
                NativeLocalSignUpResult.Unavailable -> showSignInFailure(
                    "Account creation is not enabled on this server.",
                )
                NativeLocalSignUpResult.Retryable -> showSignInFailure(
                    "The server could not create the account. Try again.",
                )
            }
        }
    }

    fun selectSecondFactor(method: NativeSecondFactor) {
        val current = mutableState.value as? RunwayUiState.SignedOut ?: return
        if (current.signingIn || current.challenge == null || method !in current.challenge.methods) return
        mutableState.value = current.copy(selectedSecondFactor = method, message = null)
    }

    fun verifyTwoFactor(code: String) {
        val current = mutableState.value as? RunwayUiState.SignedOut ?: return
        val challenge = current.challenge ?: return
        if (current.signingIn) return
        mutableState.value = current.copy(signingIn = true, message = null)
        viewModelScope.launch {
            when (
                val result = withContext(Dispatchers.IO) {
                    api.verifyTwoFactor(challenge, code, current.selectedSecondFactor)
                }
            ) {
                is NativeTwoFactorResult.Authorized -> acceptSession(result.session)
                NativeTwoFactorResult.InvalidCode -> showSignInFailure(
                    "That verification code was not accepted.",
                )
                is NativeTwoFactorResult.RateLimited -> showSignInFailure(
                    result.retryAfterSeconds?.let {
                        "Too many verification attempts. Try again in ${it}s."
                    } ?: "Too many verification attempts. Try again later.",
                )
                NativeTwoFactorResult.Expired -> {
                    val latest = mutableState.value as? RunwayUiState.SignedOut ?: return@launch
                    mutableState.value = latest.copy(
                        signingIn = false,
                        challenge = null,
                        message = "That sign-in expired. Enter your email and password again.",
                    )
                }
                NativeTwoFactorResult.Retryable -> showSignInFailure(
                    "The server could not confirm that code. Try again.",
                )
            }
        }
    }

    fun cancelTwoFactor() {
        val current = mutableState.value as? RunwayUiState.SignedOut ?: return
        if (current.signingIn) return
        mutableState.value = current.copy(challenge = null, message = null)
    }

    fun selectDestination(destination: NativeDestination) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (
            ready.destination == NativeDestination.AccountSecurity &&
            ready.actionPending
        ) {
            return
        }
        if (ready.destination == destination && ready.payload != null) return
        currentQuery = ""
        mutableState.value = ready.copy(
            destination = destination,
            payload = null,
            loading = true,
            notice = null,
            accountSecurityEphemeral = if (
                destination == NativeDestination.AccountSecurity
            ) {
                ready.accountSecurityEphemeral
            } else {
                NativeAccountSecurityEphemeral()
            },
        )
        loadView(destination, "")
    }

    fun loadCalendarMonth(month: String) {
        if (!month.matches(Regex("\\d{4}-\\d{2}"))) return
        currentQuery = "?month=$month"
        loadView(NativeDestination.Calendar, currentQuery)
    }

    fun loadMoreHistory() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (
            ready.destination != NativeDestination.History ||
            ready.loading ||
            ready.actionPending
        ) return
        val history = ready.payload as? NativeHistoryPayload ?: return
        val nextOffset = history.history?.nextOffset ?: return
        val query = "?offset=$nextOffset"
        currentQuery = query
        loadView(NativeDestination.History, query, appendHistory = true)
    }

    fun loadMoreInbox() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (
            ready.destination != NativeDestination.Inbox ||
            ready.loading ||
            ready.actionPending
        ) return
        val review = ready.payload as? NativeReviewPayload ?: return
        val nextOffset = review.activityPage?.nextOffset ?: return
        val query = "?offset=$nextOffset"
        currentQuery = query
        loadView(NativeDestination.Inbox, query, appendReview = true)
    }

    fun loadActivityTrace(activityId: String) {
        if (!UUID_PATTERN.matches(activityId)) return
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (activityId in ready.activityEvidence || activityId in ready.activityEvidenceLoading) return
        val currentSession = session ?: return expireSession()
        mutableState.value = ready.copy(
            activityEvidenceLoading = ready.activityEvidenceLoading + activityId,
            activityEvidenceFailures = ready.activityEvidenceFailures - activityId,
        )
        viewModelScope.launch {
            when (val result = withContext(Dispatchers.IO) {
                api.getView(currentSession, "activity-trace", "?activityId=$activityId")
            }) {
                is MobileViewResult.Loaded -> {
                    val evidence = result.payload as? NativeActivityEvidencePayload
                    if (evidence?.activityId != activityId || evidence.evidence == null) {
                        markActivityTraceFailed(activityId)
                        return@launch
                    }
                    val latest = mutableState.value as? RunwayUiState.Ready ?: return@launch
                    mutableState.value = latest.copy(
                        activityEvidence = latest.activityEvidence + (activityId to evidence.evidence),
                        activityEvidenceLoading = latest.activityEvidenceLoading - activityId,
                        activityEvidenceFailures = latest.activityEvidenceFailures - activityId,
                    )
                }
                MobileViewResult.Unauthorized -> expireSession()
                else -> markActivityTraceFailed(activityId)
            }
        }
    }

    private fun markActivityTraceFailed(activityId: String) {
        val latest = mutableState.value as? RunwayUiState.Ready ?: return
        mutableState.value = latest.copy(
            activityEvidenceLoading = latest.activityEvidenceLoading - activityId,
            activityEvidenceFailures = latest.activityEvidenceFailures + activityId,
        )
    }

    fun openHistoryDetail(planId: String) {
        if (!UUID_PATTERN.matches(planId)) return
        currentQuery = "?planId=$planId"
        loadView(NativeDestination.HistoryDetail, currentQuery)
    }

    fun refresh() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        loadView(ready.destination, currentQuery)
    }

    fun submitAction(command: MobileCommand) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (ready.actionPending) return
        val currentSession = session ?: return expireSession()
        val action = command.action
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
                api.runAction(currentSession, command, requestId)
            }
            if (result == MobileActionResult.Retryable) {
                delay(750)
                result = withContext(Dispatchers.IO) {
                    api.runAction(currentSession, command, requestId)
                }
            }
            when (result) {
                is MobileActionResult.Completed -> {
                    val notice = NativeNotice(
                        result.response.message.orEmpty().ifBlank { "Change saved." },
                    )
                    val preview = result.response.preview
                    if (command is PreviewableMobileCommand && preview != null) {
                        val latest = mutableState.value as? RunwayUiState.Ready ?: return@launch
                        mutableState.value = latest.copy(
                            actionPending = false,
                            notice = notice,
                            completedAction = action,
                            actionPreview = NativeActionPreview(
                                command = command,
                                preview = preview,
                            ),
                        )
                    } else if (action == "create_plan") {
                        loadBootstrap(notice)
                    } else {
                        if (action == "delete_imported_activity_data") {
                            // The server transaction has revoked this import credential and raised
                            // its generation. Only after that confirmed response may this phone
                            // release its folder grant, scheduled work, and encrypted credential.
                            withContext(Dispatchers.IO) {
                                clearNativeImportAutomation()
                            }
                        }
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

    fun requestPasswordReset() {
        runAccountOperation("request-password-reset") { currentSession ->
            api.requestPasswordReset(currentSession)
        }
    }

    fun changePassword(currentPassword: String, newPassword: String) {
        runAccountOperation("change-password") { currentSession ->
            api.changePassword(currentSession, currentPassword, newPassword)
        }
    }

    fun enableTwoFactor(password: String) {
        runAccountOperation("enable-two-factor") { currentSession ->
            api.enableTwoFactor(currentSession, password)
        }
    }

    fun verifyTwoFactorSetup(code: String) {
        runAccountOperation("verify-two-factor-setup") { currentSession ->
            api.verifyTwoFactorSetup(currentSession, code)
        }
    }

    fun disableTwoFactor(password: String) {
        runAccountOperation("disable-two-factor") { currentSession ->
            api.disableTwoFactor(currentSession, password)
        }
    }

    fun regenerateRecoveryCodes(password: String) {
        runAccountOperation("regenerate-recovery-codes") { currentSession ->
            api.regenerateRecoveryCodes(currentSession, password)
        }
    }

    fun clearTotpEnrollmentSecret() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (ready.destination != NativeDestination.AccountSecurity) return
        mutableState.value = ready.copy(
            accountSecurityEphemeral = ready.accountSecurityEphemeral.copy(totpSetup = null),
        )
    }

    fun cancelTotpSetup() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (ready.destination != NativeDestination.AccountSecurity) return
        mutableState.value = ready.copy(
            accountSecurityEphemeral = NativeAccountSecurityEphemeral(),
        )
    }

    fun clearRecoveryCodes() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (ready.destination != NativeDestination.AccountSecurity) return
        mutableState.value = ready.copy(
            accountSecurityEphemeral = ready.accountSecurityEphemeral.copy(
                recoveryCodes = emptyList(),
            ),
        )
    }

    fun clearAccountSecuritySecrets() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (
            ready.accountSecurityEphemeral.totpSetup == null &&
            ready.accountSecurityEphemeral.recoveryCodes.isEmpty()
        ) {
            return
        }
        mutableState.value = ready.copy(
            accountSecurityEphemeral =
                ready.accountSecurityEphemeral.clearSensitiveMaterialForBackground(),
        )
    }

    fun reportAuthenticatorAppUnavailable() {
        showAccountOperationFailure(
            "No authenticator app accepted the setup link. Use the manual setup key instead.",
        )
    }

    fun saveRecoveryCodes(
        resolver: ContentResolver,
        destination: Uri,
    ) {
        val ready = accountSecurityReadyState() ?: return
        val codes = ready.accountSecurityEphemeral.recoveryCodes
        if (codes.isEmpty()) return
        mutableState.value = ready.copy(actionPending = true, notice = null)
        viewModelScope.launch {
            val documentBytes = buildString {
                appendLine("runway two-factor recovery codes")
                appendLine()
                appendLine("Each code can be used once. Keep this document private.")
                appendLine()
                codes.forEach(::appendLine)
            }.toByteArray(StandardCharsets.UTF_8)
            val saved = withContext(Dispatchers.IO) {
                try {
                    val output = runCatching {
                        resolver.openOutputStream(destination, "w")
                    }.getOrNull() ?: return@withContext false
                    output.use {
                        it.write(documentBytes)
                        it.flush()
                    }
                    true
                } catch (_: Exception) {
                    false
                } finally {
                    documentBytes.fill(0)
                }
            }
            val latest = mutableState.value as? RunwayUiState.Ready ?: return@launch
            if (saved) {
                mutableState.value = latest.copy(
                    actionPending = false,
                    notice = NativeNotice(
                        "Recovery codes saved. The in-app copy has been cleared.",
                    ),
                    accountSecurityEphemeral = latest.accountSecurityEphemeral.copy(
                        recoveryCodes = emptyList(),
                    ),
                )
            } else {
                val partialRemoved = withContext(Dispatchers.IO) {
                    runCatching {
                        resolver.delete(destination, null, null) > 0
                    }.getOrDefault(false)
                }
                mutableState.value = latest.copy(
                    actionPending = false,
                    notice = NativeNotice(
                        if (partialRemoved) {
                            "Recovery codes were not saved. Choose another document."
                        } else {
                            "Recovery codes were not saved, and Android could not remove the selected document. Delete that partial document before trying again."
                        },
                        isError = true,
                    ),
                )
            }
        }
    }

    fun revokeAccountSession(sessionId: String) {
        runAccountOperation("revoke-account-session") { currentSession ->
            api.revokeAccountSession(currentSession, sessionId)
        }
    }

    fun renamePasskey(passkeyId: String, name: String) {
        runAccountOperation("rename-passkey") { currentSession ->
            api.renamePasskey(currentSession, passkeyId, name)
        }
    }

    fun deletePasskey(passkeyId: String) {
        runAccountOperation("delete-passkey") { currentSession ->
            api.deletePasskey(currentSession, passkeyId)
        }
    }

    fun exportTrainingData(
        resolver: ContentResolver,
        destination: Uri,
    ) {
        val ready = accountSecurityReadyState() ?: return
        val currentSession = session ?: return expireSession()
        mutableState.value = ready.copy(actionPending = true, notice = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val output = runCatching {
                    resolver.openOutputStream(destination, "w")
                }.getOrNull() ?: return@withContext MobileAccountOperationResult.Retryable
                output.use { api.exportTrainingData(currentSession, it) }
            }
            if (result !is MobileAccountOperationResult.Completed) {
                val partialDocumentRemoved = withContext(Dispatchers.IO) {
                    runCatching {
                        resolver.delete(destination, null, null) > 0
                    }.getOrDefault(false)
                }
                if (!partialDocumentRemoved) {
                    showAccountOperationFailure(
                        "The export did not complete, and Android could not remove the selected document. Delete that partial document from its storage provider.",
                    )
                    return@launch
                }
            }
            finishAccountOperation("export-training-data", result, currentSession)
        }
    }

    fun deleteAccount(confirmation: String) {
        val ready = accountSecurityReadyState() ?: return
        val currentSession = session ?: return expireSession()
        mutableState.value = ready.copy(actionPending = true, notice = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                api.deleteAccount(currentSession, confirmation)
            }
            if (
                result is MobileAccountOperationResult.Completed &&
                result.accountDeleted
            ) {
                authorizationJob?.cancel()
                viewLoadGate.invalidate()
                withContext(Dispatchers.IO) {
                    clearNativeImportAutomation(revokeHealthPermissions = true)
                    sessionStore.clear()
                }
                session = null
                mutableState.value = RunwayUiState.SignedOut(
                    message = "Account deleted from this server.",
                )
                loadAuthCapabilities()
                return@launch
            }
            finishAccountOperation("delete-account", result, currentSession)
        }
    }

    private fun runAccountOperation(
        action: String,
        operation: (MobileSession) -> MobileAccountOperationResult,
    ) {
        val ready = accountSecurityReadyState() ?: return
        val currentSession = session ?: return expireSession()
        mutableState.value = ready.copy(actionPending = true, notice = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { operation(currentSession) }
            finishAccountOperation(action, result, currentSession)
        }
    }

    private fun accountSecurityReadyState(): RunwayUiState.Ready? {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return null
        if (
            ready.destination != NativeDestination.AccountSecurity ||
            ready.actionPending
        ) {
            return null
        }
        return ready
    }

    private suspend fun finishAccountOperation(
        action: String,
        result: MobileAccountOperationResult,
        requestSession: MobileSession,
    ) {
        when (result) {
            is MobileAccountOperationResult.Completed -> {
                val latest = mutableState.value as? RunwayUiState.Ready ?: return
                val replacement = result.replacementSession
                if (replacement != null) {
                    val installed = withContext(Dispatchers.IO) {
                        installReplacementSession(
                            current = requestSession,
                            replacement = replacement,
                            persist = sessionStore::saveSession,
                        )
                    }
                    if (installed == null) {
                        failClosedSessionReplacement(
                            "The security change completed, but Android could not protect the new session. Sign in again.",
                        )
                        return
                    }
                    // The encrypted store has committed the replacement before the in-memory
                    // bearer changes. Never clear the old slot first.
                    session = installed
                }
                val ephemeral = when (action) {
                    "enable-two-factor" -> NativeAccountSecurityEphemeral(
                        setupPending = true,
                        totpSetup = result.totpSetup,
                    )
                    "verify-two-factor-setup",
                    "regenerate-recovery-codes",
                    -> NativeAccountSecurityEphemeral(
                        recoveryCodes = result.recoveryCodes,
                    )
                    "change-password",
                    "disable-two-factor",
                    -> NativeAccountSecurityEphemeral()
                    else -> latest.accountSecurityEphemeral
                }
                mutableState.value = latest.copy(
                    actionPending = false,
                    completedAction = action,
                    accountSecurityEphemeral = ephemeral,
                )
                loadView(
                    NativeDestination.AccountSecurity,
                    "",
                    NativeNotice(result.message),
                )
            }
            MobileAccountOperationResult.Unauthorized -> expireSession()
            is MobileAccountOperationResult.ReauthenticationRequired ->
                showAccountReauthenticationRequired(result.message)
            is MobileAccountOperationResult.SessionReplacementFailed ->
                failClosedSessionReplacement(result.message)
            is MobileAccountOperationResult.RateLimited ->
                showAccountOperationFailure(
                    result.retryAfterSeconds?.let {
                        "${result.message} Try again in ${it}s."
                    } ?: result.message,
                )
            is MobileAccountOperationResult.Rejected ->
                showAccountOperationFailure(result.message)
            MobileAccountOperationResult.Retryable ->
                showAccountOperationFailure(
                    "The server could not confirm that account-security request. Try again.",
                )
        }
    }

    private suspend fun failClosedSessionReplacement(message: String) {
        authorizationJob?.cancel()
        viewLoadGate.invalidate()
        withContext(Dispatchers.IO) { sessionStore.clear() }
        session = null
        mutableState.value = RunwayUiState.SignedOut(message = message)
        loadAuthCapabilities()
    }

    private fun showAccountOperationFailure(message: String) {
        val latest = mutableState.value as? RunwayUiState.Ready ?: return
        mutableState.value = latest.copy(
            actionPending = false,
            notice = NativeNotice(message, isError = true),
        )
    }

    private fun showAccountReauthenticationRequired(message: String) {
        val latest = mutableState.value as? RunwayUiState.Ready ?: return
        val accountSecurity = latest.payload as? NativeAccountSecurityPayload
        mutableState.value = latest.copy(
            actionPending = false,
            notice = NativeNotice(message, isError = true),
            payload = accountSecurity?.copy(
                sessions = accountSecurity.sessions?.copy(requiresFreshSession = true),
            ) ?: latest.payload,
        )
    }

    private fun clearNativeImportAutomation(revokeHealthPermissions: Boolean = false) {
        val appContext = getApplication<Application>()
        ReconciliationScheduler.cancelAll(appContext)
        if (revokeHealthPermissions) {
            AndroidHealthConnectGateway(appContext).revokeAllPermissions()
        }
        AndroidCredentialStore(appContext, origin).load()?.let { credential ->
            HandledImportStore(appContext).clearForDevice(credential.deviceId)
        }
        AndroidCredentialStore(appContext, origin).clear()
        TreeAccessStore(appContext).disconnectForReset()
        HealthConnectCursorStore(appContext, origin).clearAll()
        ShareImportRequestStore(appContext).clearAll()
        ReconciliationStatusStore(appContext).clear()
    }

    fun confirmActionPreview() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        val preview = ready.actionPreview ?: return
        submitAction(preview.command.confirmed())
    }

    fun dismissActionPreview() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        mutableState.value = ready.copy(actionPreview = null, notice = null)
    }

    fun retry() {
        when (val current = mutableState.value) {
            RunwayUiState.Loading -> Unit
            is RunwayUiState.SignedOut -> {
                when {
                    current.capabilities == null -> loadAuthCapabilities()
                    current.pending != null -> authorizationReturned(approved = true)
                    else -> mutableState.value = current.copy(message = null)
                }
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
        loadAuthCapabilities()
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
            loadAuthCapabilities()
        }
    }

    private suspend fun poll(
        pending: PendingMobileAuthorization,
        waitBeforeFirstPoll: Boolean = true,
    ) {
        var intervalSeconds = pending.pollIntervalSeconds
        var shouldWait = waitBeforeFirstPoll
        while (!pending.isExpired()) {
            if (shouldWait) delay(intervalSeconds * 1_000L)
            shouldWait = true
            when (val result = withContext(Dispatchers.IO) { api.pollAuthorization(pending) }) {
                is MobileAuthorizationPollResult.Authorized -> {
                    acceptSession(result.session)
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
                        capabilities = current?.capabilities,
                        pending = pending,
                        message = current?.message
                            ?: "Waiting for the server. Sign-in will keep checking.",
                    )
                }
            }
        }
        sessionStore.clearPending()
        mutableState.value = RunwayUiState.SignedOut(
            capabilities = (mutableState.value as? RunwayUiState.SignedOut)?.capabilities,
            message = "That sign-in request expired. Start again.",
        )
    }

    private fun loadAuthCapabilities() {
        viewModelScope.launch {
            when (
                val result = withContext(Dispatchers.IO) {
                    api.getAuthCapabilities()
                }
            ) {
                is NativeAuthCapabilitiesResult.Loaded -> {
                    val current = mutableState.value as? RunwayUiState.SignedOut ?: return@launch
                    mutableState.value = current.copy(
                        capabilities = result.capabilities,
                        message = null,
                    )
                }
                NativeAuthCapabilitiesResult.Incompatible -> {
                    showSignInFailure("This server needs an update before the native app can sign in.")
                }
                NativeAuthCapabilitiesResult.Retryable -> {
                    showSignInFailure("Could not read sign-in options from $origin.")
                }
            }
        }
    }

    private fun acceptSession(authorized: MobileSession) {
        if (!sessionStore.saveSession(authorized)) {
            val capabilities = (mutableState.value as? RunwayUiState.SignedOut)?.capabilities
            mutableState.value = RunwayUiState.SignedOut(
                capabilities = capabilities,
                message = "Android could not protect the signed-in session.",
            )
            return
        }
        session = authorized
        sessionStore.clearPending()
        authorizationJob = null
        loadBootstrap()
    }

    private fun showSignInFailure(message: String) {
        val current = mutableState.value as? RunwayUiState.SignedOut ?: return
        mutableState.value = current.copy(signingIn = false, message = message)
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
                    val bootstrap = result.payload as? NativeBootstrapPayload ?: run {
                        mutableState.value = RunwayUiState.Failed(
                            "The server returned an invalid native bootstrap response.",
                        )
                        return@launch
                    }
                    val setupComplete = bootstrap.setupComplete == true
                    val destination = if (setupComplete) {
                        NativeDestination.Calendar
                    } else {
                        NativeDestination.Setup
                    }
                    mutableState.value = RunwayUiState.Ready(
                        bootstrap = bootstrap,
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
        appendHistory: Boolean = false,
        appendReview: Boolean = false,
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
                        val payload = if (appendHistory) {
                            mergeNativeHistoryPayloads(
                                latest.payload as? NativeHistoryPayload,
                                result.payload as? NativeHistoryPayload,
                            ) ?: result.payload
                        } else if (appendReview) {
                            mergeReviewPayloads(
                                latest.payload as? NativeReviewPayload,
                                result.payload as? NativeReviewPayload,
                            ) ?: result.payload
                        } else {
                            result.payload
                        }
                        if (appendHistory || appendReview) currentQuery = ""
                        mutableState.value = latest.copy(
                            payload = payload,
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
                    if (appendHistory || appendReview) currentQuery = ""
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
                    if (appendHistory || appendReview) currentQuery = ""
                }
            }
        }
    }

    private fun mergeReviewPayloads(
        previous: NativeReviewPayload?,
        next: NativeReviewPayload?,
    ): NativeReviewPayload? {
        if (previous == null || next == null) return null
        return previous.copy(
            activities = (previous.activities + next.activities).distinctBy { it.id },
            activityPage = next.activityPage,
        )
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
        val UUID_PATTERN = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE,
        )
        val WORKOUT_PREVIEW_APPLY_ACTIONS = setOf(
            "apply_workout_edit",
            "apply_workout_add",
            "remove_workout",
        )
    }
}
