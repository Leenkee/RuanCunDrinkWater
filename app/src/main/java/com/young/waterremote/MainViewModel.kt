package com.young.waterremote

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.young.waterremote.data.AvailableRelease
import com.young.waterremote.data.DeviceBindings
import com.young.waterremote.data.GitHubUpdateChecker
import com.young.waterremote.data.InputValidator
import com.young.waterremote.data.LegacyWaterApi
import com.young.waterremote.data.RemoteApiException
import com.young.waterremote.data.SessionStore
import com.young.waterremote.data.SessionInvalidException
import com.young.waterremote.data.UserSession
import com.young.waterremote.data.WaterType
import com.young.waterremote.data.VersionComparator
import com.young.waterremote.data.calculateConsumptionAmount
import com.young.waterremote.diagnostics.DiagnosticLogs
import com.young.waterremote.updates.AutoUpdateScheduler
import com.young.waterremote.updates.AutoUpdateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

data class UiMessage(val id: Long, val text: String)

enum class DrinkingPhase {
    SENDING,
    IN_PROGRESS,
    SETTLING,
    FINISHED,
    FAILED,
    UNKNOWN
}

data class DrinkingProgress(
    val requestId: Long,
    val waterType: WaterType?,
    val phase: DrinkingPhase = DrinkingPhase.SENDING,
    val orderNumber: String? = null,
    val consumptionAmount: String? = null,
    val detail: String? = null,
    val returnCountdown: DrinkingReturnCountdown? = null,
    val secondsUntilReturn: Int = 3
)

data class AppUiState(
    val session: UserSession? = null,
    val bindings: DeviceBindings = DeviceBindings(),
    val isRequestingCode: Boolean = false,
    val isLoggingIn: Boolean = false,
    val isRefreshingBalance: Boolean = false,
    val sendingWater: WaterType? = null,
    val isSendingTemporaryWater: Boolean = false,
    val drinkingProgress: DrinkingProgress? = null,
    val isCheckingUpdate: Boolean = false,
    val isExportingLogs: Boolean = false,
    val availableRelease: AvailableRelease? = null,
    val automaticRelease: AvailableRelease? = null,
    val isAutoUpdateEnabled: Boolean = false,
    val isWaterConfirmationEnabled: Boolean = true,
    val codeCooldownSeconds: Int = 0,
    val message: UiMessage? = null
) {
    val isLoggedIn: Boolean get() = session != null
}

class MainViewModel(
    private val api: LegacyWaterApi,
    private val store: SessionStore,
    private val updateChecker: GitHubUpdateChecker,
    private val autoUpdates: AutoUpdateStore,
    private val autoUpdateScheduler: AutoUpdateScheduler
) : ViewModel() {
    private val messageIds = AtomicLong(0)
    private val drinkingRequestIds = AtomicLong(0)
    private var drinkingReturnJob: Job? = null
    private val drinkingStatusJobs = mutableMapOf<String, Job>()
    private val _state = MutableStateFlow(
        AppUiState(
            session = store.loadSession(),
            bindings = store.loadBindings(),
            isAutoUpdateEnabled = autoUpdates.isEnabled(),
            isWaterConfirmationEnabled = store.isWaterConfirmationEnabled()
        )
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            autoUpdates.observeRelease().collect { release ->
                val update = release?.takeIf {
                    VersionComparator.isNewer(it.versionName, BuildConfig.VERSION_NAME)
                }
                _state.update { it.copy(automaticRelease = update) }
                if (release != null && update == null) autoUpdates.clearRelease()
            }
        }
    }

    fun setAutoUpdateEnabled(enabled: Boolean) {
        autoUpdateScheduler.setEnabled(enabled)
        _state.update {
            it.copy(isAutoUpdateEnabled = enabled, automaticRelease = if (enabled) it.automaticRelease else null)
        }
    }

    fun requestCode(mobile: String) {
        val normalized = mobile.trim()
        if (!InputValidator.isValidMobile(normalized)) {
            showMessage("请输入正确的 11 位手机号")
            return
        }
        if (_state.value.isRequestingCode || _state.value.codeCooldownSeconds > 0) return

        viewModelScope.launch {
            _state.update { it.copy(isRequestingCode = true) }
            runCatching { api.requestCode(normalized) }
                .onSuccess {
                    _state.update { it.copy(isRequestingCode = false, codeCooldownSeconds = 60) }
                    showMessage("验证码已发送")
                    startCodeCooldown()
                }
                .onFailure {
                    _state.update { it.copy(isRequestingCode = false) }
                    showFailure(it)
                }
        }
    }

    fun login(mobile: String, code: String) {
        val normalizedMobile = mobile.trim()
        val normalizedCode = code.trim()
        when {
            !InputValidator.isValidMobile(normalizedMobile) -> {
                showMessage("请输入正确的 11 位手机号")
                return
            }
            !InputValidator.isValidCode(normalizedCode) -> {
                showMessage("请输入 4–8 位数字验证码")
                return
            }
            _state.value.isLoggingIn -> return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoggingIn = true) }
            runCatching { api.login(normalizedMobile, normalizedCode) }
                .onSuccess { session ->
                    store.saveSession(session)
                    _state.update { it.copy(session = session, isLoggingIn = false) }
                    showMessage("登录成功")
                }
                .onFailure {
                    _state.update { it.copy(isLoggingIn = false) }
                    showFailure(it)
                }
        }
    }

    fun loginWithPassword(mobile: String, password: String) {
        val normalizedMobile = mobile.trim()
        when {
            !InputValidator.isValidMobile(normalizedMobile) -> {
                showMessage("请输入正确的 11 位手机号")
                return
            }
            !InputValidator.isValidPassword(password) -> {
                showMessage("请输入密码")
                return
            }
            _state.value.isLoggingIn -> return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoggingIn = true) }
            runCatching { api.loginWithPassword(normalizedMobile, password) }
                .onSuccess { session ->
                    store.saveSession(session)
                    _state.update { it.copy(session = session, isLoggingIn = false) }
                    showMessage("登录成功")
                }
                .onFailure {
                    _state.update { it.copy(isLoggingIn = false) }
                    showFailure(it)
                }
        }
    }

    fun refreshBalance() = refreshBalance(showSuccessMessage = true)

    fun refreshBalanceSilently() = refreshBalance(showSuccessMessage = false)

    private fun refreshBalance(showSuccessMessage: Boolean) {
        val session = _state.value.session ?: run {
            showMessage("请先登录")
            return
        }
        if (_state.value.isRefreshingBalance) return

        viewModelScope.launch {
            _state.update { it.copy(isRefreshingBalance = true) }
            runCatching { api.fetchBalance(session) }
                .onSuccess { balance ->
                    if (_state.value.session?.token != session.token) return@onSuccess
                    val updatedSession = session.copy(balance = balance)
                    store.saveSession(updatedSession)
                    _state.update {
                        it.copy(session = updatedSession, isRefreshingBalance = false)
                    }
                    if (showSuccessMessage) showMessage("余额已更新")
                }
                .onFailure { error ->
                    if (_state.value.session?.token != session.token) return@onFailure
                    _state.update { it.copy(isRefreshingBalance = false) }
                    if (!handleInvalidSession(error)) showFailure(error)
                }
        }
    }

    fun saveBindings(hotKey: String, coldKey: String) {
        val bindings = DeviceBindings(hotKey.trim(), coldKey.trim())
        store.saveBindings(bindings)
        _state.update { it.copy(bindings = bindings) }
        showMessage(
            when (bindings.count) {
                0 -> "已清除设备绑定"
                1 -> "已保存 1 个出水设备"
                else -> "冷热水设备已绑定"
            }
        )
    }

    fun setWaterConfirmationEnabled(enabled: Boolean) {
        store.setWaterConfirmationEnabled(enabled)
        _state.update { it.copy(isWaterConfirmationEnabled = enabled) }
    }

    fun exportLogs(contentResolver: ContentResolver, destination: Uri) {
        if (_state.value.isExportingLogs) return
        _state.update { it.copy(isExportingLogs = true) }
        DiagnosticLogs.record("logs.export_requested")
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(destination, "wt")
                        ?: throw IOException("Cannot open log export destination")
                    output.use { DiagnosticLogs.export(it) }
                }
                DiagnosticLogs.record("logs.export_completed")
                showMessage("日志已导出")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DiagnosticLogs.failure("logs.export_failed", error)
                showMessage("日志导出失败，请重新选择保存位置")
            } finally {
                _state.update { it.copy(isExportingLogs = false) }
            }
        }
    }

    fun checkForUpdates() {
        if (_state.value.isCheckingUpdate) return
        val repository = BuildConfig.GITHUB_REPOSITORY.trim()
        if (repository.isBlank()) {
            showMessage("更新服务尚未配置")
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isCheckingUpdate = true) }
            runCatching { updateChecker.fetchLatestRelease(repository) }
                .onSuccess { release ->
                    if (VersionComparator.isNewer(release.versionName, BuildConfig.VERSION_NAME)) {
                        _state.update {
                            it.copy(isCheckingUpdate = false, availableRelease = release)
                        }
                    } else {
                        _state.update { it.copy(isCheckingUpdate = false) }
                        showMessage("当前已是最新版本（${BuildConfig.VERSION_NAME}）")
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(isCheckingUpdate = false) }
                    showFailure(error)
                }
        }
    }

    fun dismissAvailableRelease() {
        autoUpdates.clearRelease()
        _state.update { it.copy(availableRelease = null, automaticRelease = null) }
    }

    fun sendWater(type: WaterType) {
        val current = _state.value
        val deviceKey = when (type) {
            WaterType.HOT -> current.bindings.hotKey
            WaterType.COLD -> current.bindings.coldKey
        }
        if (deviceKey.isBlank()) {
            showMessage("请先绑定${type.label}设备")
            return
        }

        sendWaterCommand(
            deviceKey = deviceKey,
            sendingType = type,
            successMessage = "${type.label}指令已发送"
        )
    }

    fun sendTemporaryWater(deviceKey: String) {
        val normalized = deviceKey.trim()
        if (normalized.isBlank()) {
            showMessage("临时设备二维码无效，请重新扫描")
            return
        }

        sendWaterCommand(
            deviceKey = normalized,
            sendingType = null,
            successMessage = "临时设备出水指令已发送"
        )
    }

    private fun sendWaterCommand(
        deviceKey: String,
        sendingType: WaterType?,
        successMessage: String
    ) {
        val current = _state.value
        val session = current.session ?: run {
            showMessage("请先登录")
            return
        }
        if (current.sendingWater != null || current.isSendingTemporaryWater) return

        val requestId = drinkingRequestIds.incrementAndGet()
        DiagnosticLogs.record("water.requested", mapOf(
            "drinkingRequestId" to requestId,
            "waterType" to (sendingType?.name ?: "TEMPORARY"),
            "deviceFingerprint" to DiagnosticLogs.deviceFingerprint(deviceKey)
        ))
        _state.update {
            it.copy(
                sendingWater = sendingType,
                isSendingTemporaryWater = sendingType == null,
                drinkingProgress = DrinkingProgress(requestId, sendingType)
            )
        }
        viewModelScope.launch {
            val balanceBefore = try {
                api.fetchBalance(session)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (_state.value.session?.token != session.token) return@launch
                if (handleInvalidSession(error)) return@launch
                null
            }
            if (_state.value.session?.token != session.token) return@launch
            if (balanceBefore != null) {
                val updatedSession = _state.value.session?.copy(balance = balanceBefore) ?: return@launch
                store.saveSession(updatedSession)
                _state.update { it.copy(session = updatedSession) }
            }
            runCatching { api.sendCommand(session.token, deviceKey) }
                .onSuccess { receipt ->
                    if (_state.value.session?.token != session.token) return@onSuccess
                    _state.update {
                        it.copy(sendingWater = null, isSendingTemporaryWater = false)
                    }
                    val orderNumber = receipt.orderNumber?.takeIf { it.isNotBlank() }
                    val suffix = orderNumber?.let { "，单号 ${it.takeLast(8)}" }.orEmpty()
                    showMessage("$successMessage$suffix")
                    if (orderNumber != null) {
                        updateDrinkingProgress(requestId) {
                            it.copy(phase = DrinkingPhase.IN_PROGRESS, orderNumber = orderNumber)
                        }
                        monitorDrinkingCompletion(
                            session.copy(balance = balanceBefore), deviceKey, orderNumber, requestId
                        )
                    } else {
                        val detail = "未获取到订单号，无法自动确认接水状态。接水结束后请手动刷新余额。"
                        updateDrinkingProgress(requestId) {
                            it.copy(phase = DrinkingPhase.UNKNOWN, detail = detail)
                        }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (_state.value.session?.token != session.token) return@onFailure
                    _state.update {
                        it.copy(sendingWater = null, isSendingTemporaryWater = false)
                    }
                    if (!handleInvalidSession(error)) {
                        updateDrinkingProgress(requestId) {
                            it.copy(phase = DrinkingPhase.FAILED, detail = failureMessage(error))
                        }
                        showFailure(error)
                    }
                }
        }
    }

    fun logout() {
        drinkingStatusJobs.values.toList().forEach { it.cancel() }
        drinkingStatusJobs.clear()
        store.clearSession()
        _state.update {
            it.copy(
                session = null,
                isRefreshingBalance = false,
                sendingWater = null,
                isSendingTemporaryWater = false,
                drinkingProgress = null
            )
        }
        showMessage("已退出登录")
    }

    private fun monitorDrinkingCompletion(
        session: UserSession,
        deviceKey: String,
        orderNumber: String,
        requestId: Long
    ) {
        drinkingStatusJobs.remove(orderNumber)?.cancel()
        val job = viewModelScope.launch {
            try {
                val finished = DrinkingStatusMonitor.awaitCompletion {
                    if (_state.value.session?.token != session.token) {
                        throw CancellationException("Session changed")
                    }
                    api.fetchDrinkingStatus(
                        token = session.token,
                        deviceKey = deviceKey,
                        orderNumber = orderNumber
                    )
                }
                if (_state.value.session?.token != session.token) return@launch
                if (finished) {
                    updateDrinkingProgress(requestId) { it.copy(phase = DrinkingPhase.SETTLING) }
                    val consumptionAmount = refreshBalanceAfterDrinking(session)
                    updateDrinkingProgress(requestId) {
                        it.copy(phase = DrinkingPhase.FINISHED, consumptionAmount = consumptionAmount)
                    }
                } else {
                    val detail = "暂时无法确认接水是否结束，请查看饮水机，结束后手动刷新余额。"
                    updateDrinkingProgress(requestId) {
                        it.copy(phase = DrinkingPhase.UNKNOWN, detail = detail)
                    }
                    showMessage("未能确认本次出水结束，请手动刷新余额")
                }
            } catch (error: SessionInvalidException) {
                handleInvalidSession(error)
            }
        }
        drinkingStatusJobs[orderNumber] = job
        job.invokeOnCompletion {
            if (drinkingStatusJobs[orderNumber] === job) {
                drinkingStatusJobs.remove(orderNumber)
            }
        }
    }

    private fun dismissDrinkingProgress(requestId: Long) {
        _state.update {
            if (it.drinkingProgress?.requestId == requestId) it.copy(drinkingProgress = null) else it
        }
    }

    private fun updateDrinkingProgress(
        requestId: Long,
        update: (DrinkingProgress) -> DrinkingProgress
    ) {
        val previous = _state.value.drinkingProgress
        _state.update { state ->
            val progress = state.drinkingProgress
            if (progress?.requestId == requestId) {
                val updated = update(progress)
                val terminal = updated.phase == DrinkingPhase.FINISHED ||
                    updated.phase == DrinkingPhase.FAILED || updated.phase == DrinkingPhase.UNKNOWN
                state.copy(drinkingProgress = if (terminal && updated.returnCountdown == null) {
                    updated.copy(returnCountdown = DrinkingReturnCountdown(SystemClock.elapsedRealtime()))
                } else updated)
            } else {
                state
            }
        }
        val progress = _state.value.drinkingProgress
        if (progress?.requestId == requestId && progress != previous) {
            if (progress.returnCountdown != null && previous?.returnCountdown == null) {
                drinkingReturnJob?.cancel()
                drinkingReturnJob = viewModelScope.launch {
                    while (_state.value.drinkingProgress?.requestId == requestId) {
                        reconcileDrinkingReturn()
                        if (_state.value.drinkingProgress?.requestId != requestId) break
                        delay(100L)
                    }
                }
            }
            DiagnosticLogs.record("water.progress", mapOf(
                "drinkingRequestId" to requestId,
                "phase" to progress.phase.name,
                "orderNumber" to progress.orderNumber,
                "detail" to progress.detail,
                "consumptionAmount" to progress.consumptionAmount
            ))
        }
    }

    // elapsedRealtime includes deep sleep; reconcile on resume before drawing the screen.
    fun reconcileDrinkingReturn() {
        val progress = _state.value.drinkingProgress ?: return
        val countdown = progress.returnCountdown ?: return
        val remaining = countdown.secondsRemaining(SystemClock.elapsedRealtime())
        if (remaining == 0) {
            dismissDrinkingProgress(progress.requestId)
        } else {
            _state.update { state ->
                if (state.drinkingProgress?.requestId == progress.requestId) {
                    state.copy(drinkingProgress = state.drinkingProgress.copy(secondsUntilReturn = remaining))
                } else state
            }
        }
    }

    private suspend fun refreshBalanceAfterDrinking(session: UserSession): String? {
        val currentSession = _state.value.session
        if (currentSession?.token != session.token) return null

        val balance = try {
            api.fetchBalance(currentSession)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (_state.value.session?.token != session.token) return null
            if (!handleInvalidSession(error)) {
                showMessage("接水已结束，余额更新失败，请手动刷新")
            }
            return null
        }
        val latestSession = _state.value.session
        if (latestSession?.token != session.token) return null
        val updatedSession = latestSession.copy(balance = balance)
        store.saveSession(updatedSession)
        _state.update { it.copy(session = updatedSession) }
        showMessage("接水已结束，余额已更新")
        return calculateConsumptionAmount(session.balance, balance)
    }

    private fun handleInvalidSession(error: Throwable): Boolean {
        if (error !is SessionInvalidException) return false

        drinkingStatusJobs.values.toList().forEach { it.cancel() }
        drinkingStatusJobs.clear()
        store.clearSession()
        _state.update {
            it.copy(
                session = null,
                isRefreshingBalance = false,
                sendingWater = null,
                isSendingTemporaryWater = false,
                drinkingProgress = null
            )
        }
        showMessage("当前账号已在其他设备上登录，已退出当前登录")
        return true
    }

    fun consumeMessage(id: Long) {
        _state.update { current ->
            if (current.message?.id == id) current.copy(message = null) else current
        }
    }

    private fun startCodeCooldown() {
        viewModelScope.launch {
            while (_state.value.codeCooldownSeconds > 0) {
                delay(1_000)
                _state.update {
                    it.copy(codeCooldownSeconds = (it.codeCooldownSeconds - 1).coerceAtLeast(0))
                }
            }
        }
    }

    private fun showFailure(error: Throwable) {
        DiagnosticLogs.failure("app.operation_failed", error)
        showMessage(failureMessage(error))
    }

    private fun failureMessage(error: Throwable): String =
        when (error) {
            is RemoteApiException -> error.message
            else -> null
        }.orEmpty().ifBlank { "操作失败，请稍后再试" }

    private fun showMessage(text: String) {
        DiagnosticLogs.record("ui.message", mapOf("text" to text))
        _state.update { it.copy(message = UiMessage(messageIds.incrementAndGet(), text)) }
    }
}

class MainViewModelFactory(context: Context) : ViewModelProvider.Factory {
    private val appContext = context.applicationContext

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(
                api = LegacyWaterApi(),
                store = SessionStore(appContext),
                updateChecker = GitHubUpdateChecker(),
                autoUpdates = AutoUpdateStore(appContext),
                autoUpdateScheduler = AutoUpdateScheduler(appContext)
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
