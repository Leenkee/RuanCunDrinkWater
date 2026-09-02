package com.young.waterremote

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.young.waterremote.data.DeviceBindings
import com.young.waterremote.data.InputValidator
import com.young.waterremote.data.LegacyWaterApi
import com.young.waterremote.data.RemoteApiException
import com.young.waterremote.data.SessionStore
import com.young.waterremote.data.UserSession
import com.young.waterremote.data.WaterType
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
    val codeCooldownSeconds: Int = 0,
    val message: UiMessage? = null
) {
    val isLoggedIn: Boolean get() = session != null
}

class MainViewModel(
    private val api: LegacyWaterApi,
    private val store: SessionStore
) : ViewModel() {
    private val messageIds = AtomicLong(0)
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
                showMessage("请输入帐号密码")
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

    fun refreshBalance() {
        val session = _state.value.session ?: run {
            showMessage("请先登录")
            return
        }
        if (_state.value.isRefreshingBalance) return

        viewModelScope.launch {
            _state.update { it.copy(isRefreshingBalance = true) }
            runCatching { api.fetchBalance(session) }
                .onSuccess { balance ->
                    val updatedSession = session.copy(balance = balance)
                    store.saveSession(updatedSession)
                    _state.update {
                        it.copy(session = updatedSession, isRefreshingBalance = false)
                    }
                    showMessage("余额已更新")
                }
                .onFailure {
                    _state.update { it.copy(isRefreshingBalance = false) }
                    showFailure(it)
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

    fun sendWater(type: WaterType) {
        val current = _state.value
        val session = current.session ?: run {
            showMessage("请先登录")
            return
        }
        val deviceKey = when (type) {
            WaterType.HOT -> current.bindings.hotKey
            WaterType.COLD -> current.bindings.coldKey
        }
        if (deviceKey.isBlank()) {
            showMessage("请先绑定${type.label}设备")
            return
        }
        if (current.sendingWater != null) return

        viewModelScope.launch {
            _state.update { it.copy(sendingWater = type) }
            runCatching { api.sendCommand(session.token, deviceKey) }
                .onSuccess { receipt ->
                    _state.update { it.copy(sendingWater = null) }
                    val suffix = receipt.orderNumber?.takeIf { it.isNotBlank() }
                        ?.let { "，单号 ${it.takeLast(8)}" }
                        .orEmpty()
                    showMessage("${type.label}指令已发送$suffix")
                }
                .onFailure {
                    _state.update { it.copy(sendingWater = null) }
                    showFailure(it)
                }
        }
    }

    fun logout() {
        store.clearSession()
        _state.update { it.copy(session = null, sendingWater = null) }
        showMessage("已退出登录")
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
}

class MainViewModelFactory(context: Context) : ViewModelProvider.Factory {
    private val appContext = context.applicationContext

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(
                api = LegacyWaterApi(),
                store = SessionStore(appContext)
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
