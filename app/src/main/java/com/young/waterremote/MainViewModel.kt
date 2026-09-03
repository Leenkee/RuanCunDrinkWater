package com.young.waterremote

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.young.waterremote.data.AvailableRelease
import com.young.waterremote.data.DeviceBindings
import com.young.waterremote.data.DrinkingStatus
import com.young.waterremote.data.GitHubUpdateChecker
import com.young.waterremote.data.InputValidator
import com.young.waterremote.data.LegacyWaterApi
import com.young.waterremote.data.RemoteApiException
import com.young.waterremote.data.SessionStore
import com.young.waterremote.data.SessionInvalidException
import com.young.waterremote.data.UserSession
import com.young.waterremote.data.WaterType
import com.young.waterremote.data.VersionComparator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

data class UiMessage(val id: Long, val text: String)

data class AppUiState(
    val session: UserSession? = null,
    val bindings: DeviceBindings = DeviceBindings(),
    val isRequestingCode: Boolean = false,
    val isLoggingIn: Boolean = false,
    val isRefreshingBalance: Boolean = false,
    val sendingWater: WaterType? = null,
    val isSendingTemporaryWater: Boolean = false,
    val isCheckingUpdate: Boolean = false,
    val availableRelease: AvailableRelease? = null,
    val codeCooldownSeconds: Int = 0,
    val message: UiMessage? = null
) {
    val isLoggedIn: Boolean get() = session != null
}

class MainViewModel(
    private val api: LegacyWaterApi,
    private val store: SessionStore,
    private val updateChecker: GitHubUpdateChecker
) : ViewModel() {
    private val messageIds = AtomicLong(0)
    private val drinkingStatusJobs = mutableMapOf<String, Job>()
    private val _state = MutableStateFlow(
        AppUiState(
            session = store.loadSession(),
            bindings = store.loadBindings()
        )
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

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
        _state.update { it.copy(availableRelease = null) }
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

        viewModelScope.launch {
            _state.update {
                it.copy(
                    sendingWater = sendingType,
                    isSendingTemporaryWater = sendingType == null
                )
            }
            runCatching { api.sendCommand(session.token, deviceKey) }
                .onSuccess { receipt ->
                    _state.update {
                        it.copy(sendingWater = null, isSendingTemporaryWater = false)
                    }
                    val orderNumber = receipt.orderNumber?.takeIf { it.isNotBlank() }
                    val suffix = orderNumber?.let { "，单号 ${it.takeLast(8)}" }.orEmpty()
                    showMessage("$successMessage$suffix")
                    if (orderNumber != null) {
                        monitorDrinkingCompletion(session, deviceKey, orderNumber)
                    } else {
                        showMessage("未获取到订单号，接水结束后请手动刷新余额")
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(sendingWater = null, isSendingTemporaryWater = false)
                    }
                    showFailure(it)
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
                isSendingTemporaryWater = false
            )
        }
        showMessage("已退出登录")
    }

    private fun monitorDrinkingCompletion(
        session: UserSession,
        deviceKey: String,
        orderNumber: String
    ) {
        drinkingStatusJobs.remove(orderNumber)?.cancel()
        val job = viewModelScope.launch {
            repeat(DRINKING_STATUS_MAX_ATTEMPTS) { attempt ->
                delay(
                    when (attempt) {
                        0 -> DRINKING_STATUS_INITIAL_DELAY_MS
                        1 -> DRINKING_STATUS_SECOND_DELAY_MS
                        else -> DRINKING_STATUS_INTERVAL_MS
                    }
                )
                if (_state.value.session?.token != session.token) return@launch

                val status = runCatching {
                    api.fetchDrinkingStatus(
                        token = session.token,
                        deviceKey = deviceKey,
                        orderNumber = orderNumber
                    )
                }.getOrNull()

                if (status == DrinkingStatus.FINISHED) {
                    refreshBalanceAfterDrinking(session)
                    return@launch
                }
            }
            showMessage("未能确认本次出水结束，请手动刷新余额")
        }
        drinkingStatusJobs[orderNumber] = job
        job.invokeOnCompletion {
            if (drinkingStatusJobs[orderNumber] === job) {
                drinkingStatusJobs.remove(orderNumber)
            }
        }
    }

    private suspend fun refreshBalanceAfterDrinking(session: UserSession) {
        val currentSession = _state.value.session
        if (currentSession?.token != session.token) return

        runCatching { api.fetchBalance(currentSession) }
            .onSuccess { balance ->
                val latestSession = _state.value.session
                if (latestSession?.token != session.token) return@onSuccess
                val updatedSession = latestSession.copy(balance = balance)
                store.saveSession(updatedSession)
                _state.update { it.copy(session = updatedSession) }
                showMessage("接水已结束，余额已更新")
            }
            .onFailure { error ->
                if (!handleInvalidSession(error)) {
                    showMessage("接水已结束，余额更新失败，请手动刷新")
                }
            }
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
                isSendingTemporaryWater = false
            )
        }
        showMessage("当前帐号已在其他设备上登录，已退出当前登录")
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
        val text = when (error) {
            is RemoteApiException -> error.message
            else -> null
        }.orEmpty().ifBlank { "操作失败，请稍后再试" }
        showMessage(text)
    }

    private fun showMessage(text: String) {
        _state.update { it.copy(message = UiMessage(messageIds.incrementAndGet(), text)) }
    }

    private companion object {
        const val DRINKING_STATUS_INITIAL_DELAY_MS = 1_000L
        const val DRINKING_STATUS_SECOND_DELAY_MS = 2_000L
        const val DRINKING_STATUS_INTERVAL_MS = 5_000L
        const val DRINKING_STATUS_MAX_ATTEMPTS = 120
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
                updateChecker = GitHubUpdateChecker()
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
