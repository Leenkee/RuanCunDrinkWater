package com.young.waterremote.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.young.waterremote.AppUiState
import com.young.waterremote.MainViewModel
import com.young.waterremote.R
import com.young.waterremote.data.DeviceBindings
import com.young.waterremote.data.WaterType
import com.young.waterremote.ui.theme.ColdBlue
import com.young.waterremote.ui.theme.HotRed

private enum class MainSection(val title: String, val label: String) {
    CONTROL("山软喝水", "控制"),
    DEVICES("设备绑定", "设备"),
    ACCOUNT("我的", "我的")
}

private enum class LoginMode {
    CODE,
    PASSWORD
}

private enum class AuthPage {
    LOGIN,
    REGISTER
}

@Composable
fun WaterRemoteApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.message

    LaunchedEffect(message?.id) {
        message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.text)
        viewModel.consumeMessage(message.id)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (state.isLoggedIn) {
            MainShell(
                state = state,
                onRefreshBalance = viewModel::refreshBalance,
                onSaveBindings = viewModel::saveBindings,
                onSendWater = viewModel::sendWater,
                onLogout = viewModel::logout
            )
        } else {
            LoginScreen(
                state = state,
                onRequestCode = viewModel::requestCode,
                onRequestRegistrationCode = viewModel::requestRegistrationCode,
                onCodeLogin = viewModel::login,
                onPasswordLogin = viewModel::loginWithPassword,
                onRegister = viewModel::register,
                onRegistrationHandled = viewModel::consumeRegistrationCompleted
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = if (state.isLoggedIn) 90.dp else 20.dp
                )
                .navigationBarsPadding()
        )
    }
}

@Composable
private fun LoginScreen(
    state: AppUiState,
    onRequestCode: (String) -> Unit,
    onRequestRegistrationCode: (String) -> Unit,
    onCodeLogin: (String, String) -> Unit,
    onPasswordLogin: (String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onRegistrationHandled: () -> Unit
) {
    var mobile by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loginModeName by rememberSaveable { mutableStateOf(LoginMode.CODE.name) }
    var authPageName by rememberSaveable { mutableStateOf(AuthPage.LOGIN.name) }
    val loginMode = LoginMode.valueOf(loginModeName)
    val authPage = AuthPage.valueOf(authPageName)
    val submitBusy = if (authPage == AuthPage.REGISTER) {
        state.isRegistering
    } else {
        state.isLoggingIn
    }
    val busy = state.isRequestingCode || submitBusy

    LaunchedEffect(state.registrationCompleted) {
        if (state.registrationCompleted) {
            authPageName = AuthPage.LOGIN.name
            code = ""
            password = ""
            onRegistrationHandled()
        }
    }

    fun submitLogin() {
        when {
            authPage == AuthPage.REGISTER -> onRegister(mobile, code, password)
            loginMode == LoginMode.CODE -> onCodeLogin(mobile, code)
            else -> onPasswordLogin(mobile, password)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
        Text(
            text = if (authPage == AuthPage.REGISTER) {
                "使用手机号完成注册"
            } else {
                "登录后远程控制冷热水"
            },
            modifier = Modifier.padding(top = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(26.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                if (authPage == AuthPage.LOGIN) {
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
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "手机号注册",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(onClick = {
                            authPageName = AuthPage.LOGIN.name
                            code = ""
                            password = ""
                        }) {
                            Text("返回登录")
                        }
                    }
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
                if (authPage == AuthPage.REGISTER || loginMode == LoginMode.CODE) {
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
                            onClick = {
                                if (authPage == AuthPage.REGISTER) {
                                    onRequestRegistrationCode(mobile)
                                } else {
                                    onRequestCode(mobile)
                                }
                            },
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
                if (authPage == AuthPage.REGISTER || loginMode == LoginMode.PASSWORD) {
                    if (authPage == AuthPage.REGISTER) {
                        Spacer(modifier = Modifier.height(14.dp))
                    }
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
                            if (authPage == AuthPage.REGISTER) "注册" else "登录",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (authPage == AuthPage.LOGIN) {
                    TextButton(
                        onClick = {
                            authPageName = AuthPage.REGISTER.name
                            code = ""
                            password = ""
                        },
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 6.dp)
                    ) {
                        Text("注册手机号")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(
    state: AppUiState,
    onRefreshBalance: () -> Unit,
    onSaveBindings: (String, String) -> Unit,
    onSendWater: (WaterType) -> Unit,
    onLogout: () -> Unit
) {
    var sectionName by rememberSaveable { mutableStateOf(MainSection.CONTROL.name) }
    val section = MainSection.valueOf(sectionName)
    var pendingWater by remember { mutableStateOf<WaterType?>(null) }

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
                    if (section != MainSection.DEVICES) {
                        IconButton(
                            onClick = onRefreshBalance,
                            enabled = !state.isRefreshingBalance
                        ) {
                            if (state.isRefreshingBalance) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(21.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新余额")
                            }
                        }
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
                onRequestWater = { type ->
                    val isBound = when (type) {
                        WaterType.HOT -> state.bindings.hasHot
                        WaterType.COLD -> state.bindings.hasCold
                    }
                    if (isBound) pendingWater = type else sectionName = MainSection.DEVICES.name
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
}

@Composable
private fun ControlScreen(
    state: AppUiState,
    contentPadding: PaddingValues,
    onRefreshBalance: () -> Unit,
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
                    enabled = state.sendingWater == null,
                    onClick = { onRequestWater(WaterType.HOT) }
                )
                WaterControlCard(
                    modifier = Modifier.weight(1f),
                    type = WaterType.COLD,
                    bound = state.bindings.hasCold,
                    loading = state.sendingWater == WaterType.COLD,
                    enabled = state.sendingWater == null,
                    onClick = { onRequestWater(WaterType.COLD) }
                )
            }
        }
        item {
            DeviceStatusCard(bindings = state.bindings)
        }
        item {
            SafetyNotice()
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
                    bound -> "点击立即出水"
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
private fun SafetyNotice() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(15.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer
        )
        Text(
            text = "远程出水前请确认杯子已放稳，并注意热水烫伤风险。",
            modifier = Modifier.padding(start = 11.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium
        )
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
        text = {
            Text(
                if (type == WaterType.HOT) {
                    "确认杯子已经放稳、出水口附近无人触碰。热水可能造成烫伤，确认后将立即发送出水指令。"
                } else {
                    "确认杯子已经放稳。确认后将立即向饮水机发送冷水出水指令。"
                }
            )
        },
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
    setOrientationLocked(false)
}
