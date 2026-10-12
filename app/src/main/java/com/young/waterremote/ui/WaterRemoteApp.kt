package com.young.waterremote.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.young.waterremote.AppUiState
import com.young.waterremote.BuildConfig
import com.young.waterremote.DrinkingPhase
import com.young.waterremote.DrinkingProgress
import com.young.waterremote.MainViewModel
import com.young.waterremote.PortraitCaptureActivity
import com.young.waterremote.R
import com.young.waterremote.WidgetAction
import com.young.waterremote.WidgetActionRequest
import com.young.waterremote.data.DeviceBindings
import com.young.waterremote.data.AvailableRelease
import com.young.waterremote.data.WaterType
import com.young.waterremote.ui.theme.ColdBlue
import com.young.waterremote.ui.theme.HotRed
import kotlinx.coroutines.delay

private enum class MainSection(val title: String, val label: String) {
    CONTROL("山软喝水", "控制"),
    DEVICES("设备绑定", "设备"),
    ACCOUNT("我的", "我的")
}

private enum class LoginMode {
    CODE,
    PASSWORD
}

private enum class SettingsPage(val title: String) {
    ROOT("设置"),
    VERSION("版本"),
    HELP("使用帮助"),
    PROMOTION("推广")
}

private const val IOS_WATER_URL = "https://water.simulacra.me/"
private const val DRINKING_RETURN_COUNTDOWN_SECONDS = 3

@Composable
fun WaterRemoteApp(
    viewModel: MainViewModel,
    widgetActionRequest: WidgetActionRequest?,
    onWidgetActionConsumed: (Long) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var settingsPageName by rememberSaveable { mutableStateOf(SettingsPage.ROOT.name) }
    val settingsPage = SettingsPage.valueOf(settingsPageName)
    val message = state.message

    BackHandler(enabled = showSettings) {
        if (settingsPage == SettingsPage.ROOT) {
            showSettings = false
        } else {
            settingsPageName = SettingsPage.ROOT.name
        }
    }

    LaunchedEffect(message?.id) {
        message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.text)
        viewModel.consumeMessage(message.id)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (showSettings) {
            SettingsScreen(
                state = state,
                page = settingsPage,
                onBack = {
                    if (settingsPage == SettingsPage.ROOT) {
                        showSettings = false
                    } else {
                        settingsPageName = SettingsPage.ROOT.name
                    }
                },
                onWaterConfirmationChange = viewModel::setWaterConfirmationEnabled,
                onAutoUpdateChange = viewModel::setAutoUpdateEnabled,
                onCheckForUpdates = viewModel::checkForUpdates,
                onOpenVersion = { settingsPageName = SettingsPage.VERSION.name },
                onOpenHelp = { settingsPageName = SettingsPage.HELP.name },
                onOpenPromotion = { settingsPageName = SettingsPage.PROMOTION.name },
                onOpenIosWater = { uriHandler.openUri(IOS_WATER_URL) }
            )
        } else if (state.isLoggedIn) {
            MainShell(
                state = state,
                onOpenSettings = {
                    settingsPageName = SettingsPage.ROOT.name
                    showSettings = true
                },
                onRefreshBalance = viewModel::refreshBalance,
                onAccountEntered = viewModel::refreshBalanceSilently,
                onSaveBindings = viewModel::saveBindings,
                onSendWater = viewModel::sendWater,
                onSendTemporaryWater = viewModel::sendTemporaryWater,
                onDismissDrinkingProgress = viewModel::dismissDrinkingProgress,
                onLogout = viewModel::logout,
                isResumed = isResumed,
                widgetActionRequest = widgetActionRequest,
                onWidgetActionConsumed = onWidgetActionConsumed
            )
        } else {
            LoginScreen(
                state = state,
                onOpenSettings = {
                    settingsPageName = SettingsPage.ROOT.name
                    showSettings = true
                },
                onRequestCode = viewModel::requestCode,
                onCodeLogin = viewModel::login,
                onPasswordLogin = viewModel::loginWithPassword
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = if (state.isLoggedIn && !showSettings && state.drinkingProgress == null) {
                        90.dp
                    } else {
                        20.dp
                    }
                )
                .navigationBarsPadding()
        )
    }

    (state.availableRelease ?: state.automaticRelease?.takeIf { isResumed })?.let { release ->
        UpdateAvailableDialog(
            release = release,
            onDismiss = viewModel::dismissAvailableRelease,
            onDownload = {
                viewModel.dismissAvailableRelease()
                uriHandler.openUri(release.downloadUrl)
            }
        )
    }
}

@Composable
private fun LoginScreen(
    state: AppUiState,
    onOpenSettings: () -> Unit,
    onRequestCode: (String) -> Unit,
    onCodeLogin: (String, String) -> Unit,
    onPasswordLogin: (String, String) -> Unit
) {
    var mobile by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loginModeName by rememberSaveable { mutableStateOf(LoginMode.CODE.name) }
    val loginMode = LoginMode.valueOf(loginModeName)
    val submitBusy = state.isLoggingIn
    val busy = state.isRequestingCode || submitBusy

    fun submitLogin() {
        when (loginMode) {
            LoginMode.CODE -> onCodeLogin(mobile, code)
            LoginMode.PASSWORD -> onPasswordLogin(mobile, password)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "山软喝水",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(26.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilterChip(
                        selected = loginMode == LoginMode.CODE,
                        onClick = { loginModeName = LoginMode.CODE.name },
                        label = { Text("验证码登录") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = loginMode == LoginMode.PASSWORD,
                        onClick = { loginModeName = LoginMode.PASSWORD.name },
                        label = { Text("密码登录") },
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = mobile,
                    onValueChange = { value ->
                        if (value.length <= 11 && value.all(Char::isDigit)) mobile = value
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !submitBusy,
                    singleLine = true,
                    label = { Text("手机号") },
                    leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Next
                    )
                )
                Spacer(modifier = Modifier.height(14.dp))
                if (loginMode == LoginMode.CODE) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { value ->
                                if (value.length <= 8 && value.all(Char::isDigit)) code = value
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !submitBusy,
                            singleLine = true,
                            label = { Text("验证码") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { submitLogin() })
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        FilledTonalButton(
                            onClick = { onRequestCode(mobile) },
                            enabled = !busy && state.codeCooldownSeconds == 0,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp)
                        ) {
                            if (state.isRequestingCode) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    if (state.codeCooldownSeconds > 0) {
                                        "${state.codeCooldownSeconds}s"
                                    } else {
                                        "获取验证码"
                                    }
                                )
                            }
                        }
                    }
                }
                if (loginMode == LoginMode.PASSWORD) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { value ->
                            if (value.length <= 128) password = value
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !submitBusy,
                        singleLine = true,
                        label = { Text("密码") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submitLogin() })
                    )
                }

                Button(
                    onClick = { submitLogin() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp)
                        .height(52.dp),
                    enabled = !busy,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (submitBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            "登录",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Text(
                    text = "温馨提示：建议使用密码登录。小天同学短信验证码重复发送间隔达数小时，可能影响登录体验。",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        }
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 4.dp, end = 8.dp)
        ) {
            Icon(Icons.Default.Settings, contentDescription = "设置")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(
    state: AppUiState,
    onOpenSettings: () -> Unit,
    onRefreshBalance: () -> Unit,
    onAccountEntered: () -> Unit,
    onSaveBindings: (String, String) -> Unit,
    onSendWater: (WaterType) -> Unit,
    onSendTemporaryWater: (String) -> Unit,
    onDismissDrinkingProgress: (Long) -> Unit,
    onLogout: () -> Unit,
    isResumed: Boolean,
    widgetActionRequest: WidgetActionRequest?,
    onWidgetActionConsumed: (Long) -> Unit
) {
    val context = LocalContext.current
    var sectionName by rememberSaveable { mutableStateOf(MainSection.CONTROL.name) }
    val section = MainSection.valueOf(sectionName)
    var pendingWater by remember { mutableStateOf<WaterType?>(null) }
    var pendingTemporaryDeviceKey by rememberSaveable { mutableStateOf<String?>(null) }
    val drinkingProgress = state.drinkingProgress
    var secondsUntilReturn by remember(drinkingProgress?.requestId, drinkingProgress?.phase) {
        mutableStateOf(DRINKING_RETURN_COUNTDOWN_SECONDS)
    }

    LaunchedEffect(section) {
        if (section == MainSection.ACCOUNT) onAccountEntered()
    }

    val temporaryScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents?.trim()
        if (contents.isNullOrBlank()) {
            Toast.makeText(context, "未扫描到二维码", Toast.LENGTH_SHORT).show()
        } else if (state.isWaterConfirmationEnabled) {
            pendingTemporaryDeviceKey = contents
        } else {
            onSendTemporaryWater(contents)
        }
    }
    val temporaryCameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            temporaryScanLauncher.launch(temporaryScanOptions())
        } else {
            Toast.makeText(context, "需要相机权限才能扫描临时设备", Toast.LENGTH_SHORT).show()
        }
    }

    fun scanTemporaryDevice() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            temporaryScanLauncher.launch(temporaryScanOptions())
        } else {
            temporaryCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(widgetActionRequest?.id, drinkingProgress?.requestId) {
        if (drinkingProgress != null) return@LaunchedEffect
        val request = widgetActionRequest ?: return@LaunchedEffect
        when (request.action) {
            WidgetAction.HOT, WidgetAction.COLD -> {
                sectionName = MainSection.CONTROL.name
                pendingTemporaryDeviceKey = null
                val type = if (request.action == WidgetAction.HOT) {
                    WaterType.HOT
                } else {
                    WaterType.COLD
                }
                val isBound = when (type) {
                    WaterType.HOT -> state.bindings.hasHot
                    WaterType.COLD -> state.bindings.hasCold
                }
                if (isBound) {
                    if (state.isWaterConfirmationEnabled) {
                        pendingWater = type
                    } else {
                        onSendWater(type)
                    }
                } else {
                    sectionName = MainSection.DEVICES.name
                    Toast.makeText(context, "请先绑定${type.label}设备", Toast.LENGTH_SHORT).show()
                }
            }
            WidgetAction.TEMPORARY_SCAN -> {
                sectionName = MainSection.CONTROL.name
                pendingWater = null
                scanTemporaryDevice()
            }
        }
        onWidgetActionConsumed(request.id)
    }

    if (drinkingProgress != null) {
        val returnToMainMenu = {
            sectionName = MainSection.CONTROL.name
            onDismissDrinkingProgress(drinkingProgress.requestId)
        }
        val isTerminal = drinkingProgress.phase == DrinkingPhase.FINISHED ||
            drinkingProgress.phase == DrinkingPhase.FAILED ||
            drinkingProgress.phase == DrinkingPhase.UNKNOWN
        BackHandler { }
        LaunchedEffect(drinkingProgress.requestId, drinkingProgress.phase, isResumed) {
            if (isTerminal && isResumed) {
                for (seconds in DRINKING_RETURN_COUNTDOWN_SECONDS downTo 1) {
                    secondsUntilReturn = seconds
                    delay(1_000L)
                }
                returnToMainMenu()
            }
        }
        DrinkingProgressScreen(
            progress = drinkingProgress,
            secondsUntilReturn = secondsUntilReturn
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = section.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                MainSection.entries.forEach { item ->
                    val icon = when (item) {
                        MainSection.CONTROL -> Icons.Default.PlayArrow
                        MainSection.DEVICES -> Icons.Default.Build
                        MainSection.ACCOUNT -> Icons.Default.Person
                    }
                    NavigationBarItem(
                        selected = section == item,
                        onClick = { sectionName = item.name },
                        icon = { Icon(icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { contentPadding ->
        when (section) {
            MainSection.CONTROL -> ControlScreen(
                state = state,
                contentPadding = contentPadding,
                onRefreshBalance = onRefreshBalance,
                onRequestTemporaryWater = { scanTemporaryDevice() },
                onRequestWater = { type ->
                    val isBound = when (type) {
                        WaterType.HOT -> state.bindings.hasHot
                        WaterType.COLD -> state.bindings.hasCold
                    }
                    if (!isBound) {
                        sectionName = MainSection.DEVICES.name
                    } else if (state.isWaterConfirmationEnabled) {
                        pendingWater = type
                    } else {
                        onSendWater(type)
                    }
                }
            )
            MainSection.DEVICES -> DevicesScreen(
                bindings = state.bindings,
                contentPadding = contentPadding,
                onSaveBindings = onSaveBindings
            )
            MainSection.ACCOUNT -> AccountScreen(
                state = state,
                contentPadding = contentPadding,
                onRefreshBalance = onRefreshBalance,
                onLogout = onLogout
            )
        }
    }

    pendingWater?.let { type ->
        WaterConfirmationDialog(
            type = type,
            onDismiss = { pendingWater = null },
            onConfirm = {
                pendingWater = null
                onSendWater(type)
            }
        )
    }

    pendingTemporaryDeviceKey?.let { deviceKey ->
        TemporaryWaterConfirmationDialog(
            onDismiss = { pendingTemporaryDeviceKey = null },
            onConfirm = {
                pendingTemporaryDeviceKey = null
                onSendTemporaryWater(deviceKey)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrinkingProgressScreen(
    progress: DrinkingProgress,
    secondsUntilReturn: Int
) {
    val accent = when (progress.phase) {
        DrinkingPhase.FAILED, DrinkingPhase.UNKNOWN -> MaterialTheme.colorScheme.error
        else -> when (progress.waterType) {
            WaterType.HOT -> HotRed
            WaterType.COLD -> ColdBlue
            null -> MaterialTheme.colorScheme.primary
        }
    }
    val title = when (progress.phase) {
        DrinkingPhase.SENDING -> "正在发送接水指令"
        DrinkingPhase.IN_PROGRESS -> "可接水"
        DrinkingPhase.SETTLING, DrinkingPhase.FINISHED -> "接水结束"
        DrinkingPhase.FAILED -> "接水指令发送失败"
        DrinkingPhase.UNKNOWN -> "暂时无法确认接水状态"
    }
    val description = when (progress.phase) {
        DrinkingPhase.SENDING -> "正在联系饮水机，请稍候"
        DrinkingPhase.IN_PROGRESS -> "按动饮水机接水按钮可进行接水"
        DrinkingPhase.SETTLING -> "正在更新余额，计算本次消费"
        DrinkingPhase.FINISHED -> "$secondsUntilReturn 秒后自动返回主菜单"
        DrinkingPhase.FAILED, DrinkingPhase.UNKNOWN ->
            "${progress.detail.orEmpty()}\n$secondsUntilReturn 秒后自动返回主菜单"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("接水状态", fontWeight = FontWeight.SemiBold) }
            )
        }
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(88.dp),
                            shape = CircleShape,
                            color = accent.copy(alpha = 0.12f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (progress.phase == DrinkingPhase.IN_PROGRESS) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_filling_cup),
                                        contentDescription = null,
                                        modifier = Modifier.size(68.dp),
                                        tint = Color.Unspecified
                                    )
                                } else {
                                    Icon(
                                        imageVector = when (progress.phase) {
                                            DrinkingPhase.SENDING -> Icons.AutoMirrored.Filled.Send
                                            DrinkingPhase.SETTLING, DrinkingPhase.FINISHED -> Icons.Default.CheckCircle
                                            else -> Icons.Default.Info
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = accent
                                    )
                                }
                            }
                        }
                        Text(
                            text = progress.waterType?.let { "${it.label}接水" } ?: "临时设备接水",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        if (progress.phase == DrinkingPhase.SETTLING || progress.phase == DrinkingPhase.FINISHED) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "本次消费",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    text = if (progress.phase == DrinkingPhase.SETTLING) {
                                        "计算中…"
                                    } else {
                                        progress.consumptionAmount?.let { "¥ $it" } ?: "暂未获取"
                                    },
                                    color = accent,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        Text(
                            text = description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                        progress.orderNumber?.let { orderNumber ->
                            Text(
                                text = "单号 ${orderNumber.takeLast(8)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    state: AppUiState,
    page: SettingsPage,
    onBack: () -> Unit,
    onWaterConfirmationChange: (Boolean) -> Unit,
    onAutoUpdateChange: (Boolean) -> Unit,
    onCheckForUpdates: () -> Unit,
    onOpenVersion: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenPromotion: () -> Unit,
    onOpenIosWater: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page.title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(
                start = 18.dp,
                top = contentPadding.calculateTopPadding() + 12.dp,
                end = 18.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (page) {
                SettingsPage.ROOT -> {
                    item {
                        Text(
                            text = "应用设置",
                            modifier = Modifier.padding(start = 4.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            ListItem(
                                headlineContent = { Text("出水二次确认") },
                                supportingContent = {
                                    Text(
                                        if (state.isWaterConfirmationEnabled) {
                                            "发送出水指令前需要再次确认"
                                        } else {
                                            "点击出水或完成扫码后直接发送指令"
                                        }
                                    )
                                },
                                leadingContent = {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                },
                                trailingContent = {
                                    Switch(
                                        checked = state.isWaterConfirmationEnabled,
                                        onCheckedChange = onWaterConfirmationChange
                                    )
                                },
                                modifier = Modifier.clickable {
                                    onWaterConfirmationChange(
                                        !state.isWaterConfirmationEnabled
                                    )
                                }
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                            SettingsActionRow(
                                title = "版本",
                                description = "当前版本 ${BuildConfig.VERSION_NAME}",
                                icon = Icons.Default.CheckCircle,
                                onClick = onOpenVersion
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                            SettingsActionRow(
                                title = "使用帮助",
                                description = "了解登录、设备、余额和桌面小组件",
                                icon = Icons.Default.Person,
                                onClick = onOpenHelp
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                            SettingsActionRow(
                                title = "推广",
                                description = "iOS 用户喝水入口",
                                icon = Icons.Default.Phone,
                                onClick = onOpenPromotion
                            )
                        }
                    }
                }

                SettingsPage.VERSION -> {
                    item {
                        Text(
                            text = "版本设置",
                            modifier = Modifier.padding(start = 4.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            ListItem(
                                headlineContent = { Text("自动检查更新") },
                                supportingContent = {
                                    Text(
                                        if (state.isAutoUpdateEnabled) {
                                            "每天中午 12:00 检查，系统限制可能导致延迟"
                                        } else {
                                            "已关闭，仅在手动操作时检查"
                                        }
                                    )
                                },
                                leadingContent = {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                },
                                trailingContent = {
                                    Switch(
                                        checked = state.isAutoUpdateEnabled,
                                        onCheckedChange = onAutoUpdateChange
                                    )
                                },
                                modifier = Modifier.clickable {
                                    onAutoUpdateChange(!state.isAutoUpdateEnabled)
                                }
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                            SettingsActionRow(
                                title = "检查更新",
                                description = "当前版本 ${BuildConfig.VERSION_NAME}",
                                icon = Icons.Default.Refresh,
                                loading = state.isCheckingUpdate,
                                onClick = onCheckForUpdates
                            )
                        }
                    }
                }

                SettingsPage.HELP -> {
                    item {
                        HelpCard(
                            icon = Icons.Default.Person,
                            title = "开始使用",
                            description = "本应用不提供账号注册。请先使用“小天同学”微信小程序或 APP 完成注册，再使用相同手机号登录本应用。"
                        )
                    }
                    item {
                        HelpCard(
                            icon = Icons.Default.Build,
                            title = "绑定喝水设备",
                            description = "在“设备”页面分别扫描热水和冷水设备二维码。绑定后会保存在本机，日常喝水无需每次扫码。"
                        )
                    }
                    item {
                        HelpCard(
                            icon = Icons.Default.PlayArrow,
                            title = "查看接水状态",
                            description = "发送接水指令后进入接水状态页面，接水期间无法手动返回。接水完成后显示“接水结束”和本次消费，倒计时 3 秒自动返回主菜单。发生异常时也会显示原因并倒计时返回。"
                        )
                    }
                    item {
                        HelpCard(
                            icon = Icons.Default.Refresh,
                            title = "查看账户余额",
                            description = "控制页面和“我的”页面会显示小天同学喝水账户余额；进入“我的”或饮水结束后会自动刷新。"
                        )
                    }
                    item {
                        HelpCard(
                            icon = Icons.Default.PlayArrow,
                            title = "桌面小组件",
                            description = "长按手机桌面空白处添加“山软喝水”小组件，即可快速开启热水、冷水或扫描临时设备。"
                        )
                    }
                }

                SettingsPage.PROMOTION -> {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(22.dp)) {
                                Text(
                                    text = "iOS 用户也能方便喝水",
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "iPhone 用户可使用以下网页服务：\n$IOS_WATER_URL",
                                    modifier = Modifier.padding(top = 10.dp),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            SettingsActionRow(
                                title = "打开网页",
                                description = IOS_WATER_URL,
                                icon = Icons.Default.Phone,
                                onClick = onOpenIosWater
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    description: String,
    icon: ImageVector,
    loading: Boolean = false,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = if (loading) {
            {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        } else null,
        modifier = Modifier.clickable(enabled = !loading, role = Role.Button, onClick = onClick)
    )
}

@Composable
private fun HelpCard(
    icon: ImageVector,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    modifier = Modifier.padding(top = 5.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun UpdateAvailableDialog(
    release: AvailableRelease,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
        title = { Text("发现新版本 ${release.versionName}") },
        text = {
            Text("当前版本为 ${BuildConfig.VERSION_NAME}，可前往下载并安装最新版本。")
        },
        confirmButton = {
            Button(onClick = onDownload) { Text("下载更新") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后") }
        }
    )
}

@Composable
private fun ControlScreen(
    state: AppUiState,
    contentPadding: PaddingValues,
    onRefreshBalance: () -> Unit,
    onRequestTemporaryWater: () -> Unit,
    onRequestWater: (WaterType) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 18.dp,
            top = contentPadding.calculateTopPadding() + 12.dp,
            end = 18.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            BalanceCard(
                balance = state.session?.balance,
                isRefreshing = state.isRefreshingBalance,
                onRefresh = onRefreshBalance
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                WaterControlCard(
                    modifier = Modifier.weight(1f),
                    type = WaterType.HOT,
                    bound = state.bindings.hasHot,
                    loading = state.sendingWater == WaterType.HOT,
                    enabled = state.sendingWater == null && !state.isSendingTemporaryWater,
                    onClick = { onRequestWater(WaterType.HOT) }
                )
                WaterControlCard(
                    modifier = Modifier.weight(1f),
                    type = WaterType.COLD,
                    bound = state.bindings.hasCold,
                    loading = state.sendingWater == WaterType.COLD,
                    enabled = state.sendingWater == null && !state.isSendingTemporaryWater,
                    onClick = { onRequestWater(WaterType.COLD) }
                )
            }
        }
        item {
            OutlinedButton(
                onClick = onRequestTemporaryWater,
                enabled = state.sendingWater == null && !state.isSendingTemporaryWater,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                if (state.isSendingTemporaryWater) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_qr_code),
                        contentDescription = null
                    )
                }
                Text(
                    text = if (state.isSendingTemporaryWater) {
                        "正在向临时设备发送指令…"
                    } else {
                        "扫码临时设备喝水"
                    },
                    modifier = Modifier.padding(start = 9.dp)
                )
            }
        }
        item {
            DeviceStatusCard(bindings = state.bindings)
        }
    }
}

@Composable
private fun BalanceCard(
    balance: String?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isRefreshing, onClick = onRefresh),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "余额",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = balance?.let { "¥ $it" } ?: "¥ --",
                    modifier = Modifier.padding(top = 5.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (balance == null) "点击查询余额" else "点击可刷新",
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
            } else {
                Surface(
                    modifier = Modifier.size(46.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.padding(11.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun WaterControlCard(
    modifier: Modifier,
    type: WaterType,
    bound: Boolean,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val accent = if (type == WaterType.HOT) HotRed else ColdBlue
    Card(
        modifier = modifier
            .height(190.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                color = accent
            ) {
                if (loading) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(30.dp),
                            color = Color.White,
                            strokeWidth = 3.dp
                        )
                    }
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = type.label.take(1),
                            color = Color.White,
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Text(
                text = type.label,
                modifier = Modifier.padding(top = 13.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            Text(
                text = when {
                    loading -> "正在发送指令…"
                    bound -> "点击开启饮水机"
                    else -> "未绑定 · 去绑定"
                },
                modifier = Modifier.padding(top = 5.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun DeviceStatusCard(bindings: DeviceBindings) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (bindings.count == 2) Icons.Default.CheckCircle else Icons.Default.Build,
                contentDescription = null,
                tint = if (bindings.count == 2) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text("设备状态", fontWeight = FontWeight.SemiBold)
                Text(
                    text = when (bindings.count) {
                        0 -> "尚未绑定冷热水设备"
                        1 -> "已绑定 1 个设备，还可继续绑定"
                        else -> "冷热水设备均已就绪"
                    },
                    modifier = Modifier.padding(top = 3.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun DevicesScreen(
    bindings: DeviceBindings,
    contentPadding: PaddingValues,
    onSaveBindings: (String, String) -> Unit
) {
    val context = LocalContext.current
    var hotKey by rememberSaveable(bindings.hotKey) { mutableStateOf(bindings.hotKey) }
    var coldKey by rememberSaveable(bindings.coldKey) { mutableStateOf(bindings.coldKey) }
    var scanTargetName by rememberSaveable { mutableStateOf(WaterType.HOT.name) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) {
            Toast.makeText(context, "未扫描到二维码", Toast.LENGTH_SHORT).show()
        } else if (scanTargetName == WaterType.HOT.name) {
            hotKey = contents
        } else {
            coldKey = contents
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scanLauncher.launch(scanOptions(scanTargetName))
        } else {
            Toast.makeText(context, "需要相机权限才能扫码绑定", Toast.LENGTH_SHORT).show()
        }
    }

    fun startScan(type: WaterType) {
        scanTargetName = type.name
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            scanLauncher.launch(scanOptions(type.name))
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 18.dp,
            top = contentPadding.calculateTopPadding() + 12.dp,
            end = 18.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "绑定出水设备",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "分别扫描热水与冷水出水口的二维码，也可以手动粘贴设备内容。",
                        modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    DeviceKeyField(
                        value = hotKey,
                        onValueChange = { hotKey = it },
                        type = WaterType.HOT,
                        onScan = { startScan(WaterType.HOT) }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    DeviceKeyField(
                        value = coldKey,
                        onValueChange = { coldKey = it },
                        type = WaterType.COLD,
                        onScan = { startScan(WaterType.COLD) }
                    )
                    Button(
                        onClick = { onSaveBindings(hotKey, coldKey) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 22.dp)
                            .height(50.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                        Text("保存绑定", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(15.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Default.Build, contentDescription = null)
                Text(
                    "二维码内容只保存在本机。若扫描错误，可清空对应输入框后重新保存。",
                    modifier = Modifier.padding(start = 11.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun DeviceKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    type: WaterType,
    onScan: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("${type.label}设备码") },
        placeholder = { Text("扫描或粘贴二维码内容") },
        supportingText = {
            Text(if (value.isBlank()) "未绑定" else "已录入，可点击保存")
        },
        trailingIcon = {
            IconButton(onClick = onScan) {
                Icon(
                    painter = painterResource(R.drawable.ic_qr_code),
                    contentDescription = "扫描${type.label}二维码"
                )
            }
        }
    )
}

@Composable
private fun AccountScreen(
    state: AppUiState,
    contentPadding: PaddingValues,
    onRefreshBalance: () -> Unit,
    onLogout: () -> Unit
) {
    var showLogoutDialog by remember { mutableStateOf(false) }
    val session = state.session ?: return

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 18.dp,
            top = contentPadding.calculateTopPadding() + 12.dp,
            end = 18.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        modifier = Modifier.size(76.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.padding(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = maskMobile(session.mobile),
                        modifier = Modifier.padding(top = 14.dp),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "已登录",
                        modifier = Modifier.padding(top = 3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))
                    Text(
                        "余额",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = session.balance?.let { "¥ $it" } ?: "¥ --",
                        modifier = Modifier.padding(top = 5.dp),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    FilledTonalButton(
                        onClick = onRefreshBalance,
                        modifier = Modifier.padding(top = 14.dp),
                        enabled = !state.isRefreshingBalance
                    ) {
                        if (state.isRefreshingBalance) {
                            CircularProgressIndicator(modifier = Modifier.size(19.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Text("刷新余额", modifier = Modifier.padding(start = 7.dp))
                        }
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { showLogoutDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                Text("退出登录", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null) },
            title = { Text("退出登录？") },
            text = { Text("退出后将移除本机保存的登录信息，设备绑定会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    onLogout()
                }) {
                    Text("退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun WaterConfirmationDialog(
    type: WaterType,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val accent = if (type == WaterType.HOT) HotRed else ColdBlue
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(modifier = Modifier.size(52.dp), shape = CircleShape, color = accent) {
                Box(contentAlignment = Alignment.Center) {
                    Text(type.label.take(1), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        },
        title = { Text("启动${type.label}？") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = accent)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Text("确认出水", modifier = Modifier.padding(start = 7.dp))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun TemporaryWaterConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_qr_code),
                    contentDescription = null,
                    modifier = Modifier.padding(13.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        title = { Text("使用临时设备出水？") },
        text = {
            Text(
                "确认后将立即发送出水指令，本次扫描的设备不会保存到应用中。"
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Text("确认出水", modifier = Modifier.padding(start = 7.dp))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun maskMobile(mobile: String): String {
    if (mobile.length < 7) return mobile.ifBlank { "已登录手机号" }
    return mobile.take(3) + " **** " + mobile.takeLast(4)
}

private fun scanOptions(typeName: String) = ScanOptions().apply {
    val label = if (typeName == WaterType.HOT.name) "热水" else "冷水"
    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    setPrompt("扫描${label}设备二维码")
    setBeepEnabled(true)
    setBarcodeImageEnabled(false)
    setCaptureActivity(PortraitCaptureActivity::class.java)
    setOrientationLocked(true)
}

private fun temporaryScanOptions() = ScanOptions().apply {
    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    setPrompt("扫描临时设备二维码")
    setBeepEnabled(true)
    setBarcodeImageEnabled(false)
    setCaptureActivity(PortraitCaptureActivity::class.java)
    setOrientationLocked(true)
}
