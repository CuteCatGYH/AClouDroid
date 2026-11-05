package app.acloudroid.qwq.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val cookieDiagnostic by viewModel.cookieDiagnostic.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "AClouDroid",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val state = uiState) {
                // P2-013: never render NotLoggedInContent (and thus ICloudWebView) while the
                // persisted session is still being read. A bare spinner costs nothing; building
                // a WebView only to tear it down cost ~1.5s of blocked main thread on device.
                is AuthUiState.Checking -> {
                    CheckingContent()
                }
                is AuthUiState.NotLoggedIn -> {
                    NotLoggedInContent(
                        isChinaMainland = state.isChinaMainland,
                        showSessionExpiredAlert = state.showSessionExpiredAlert,
                        diagnostics = cookieDiagnostic,
                        cookieProbe = viewModel.cookieProbe,
                        onRegionSelected = { viewModel.setRegion(it) },
                        onCookiesExtracted = { viewModel.onCookiesExtracted(it) },
                        onCookieDiagnostic = { viewModel.onCookieDiagnostic(it) },
                        onForceExtract = { viewModel.forceExtractCookies() }
                    )
                }
                is AuthUiState.Authenticating -> {
                    AuthenticatingContent(message = state.message)
                }
                is AuthUiState.LoggedIn -> {
                    LoggedInContent(
                        state = state,
                        onLogout = { viewModel.logout() }
                    )
                }
                is AuthUiState.Error -> {
                    ErrorContent(
                        errorMessage = state.errorMessage,
                        onRetry = { viewModel.setRegion(state.isChinaMainland, showExpiredAlert = true) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotLoggedInContent(
    isChinaMainland: Boolean,
    showSessionExpiredAlert: Boolean,
    diagnostics: CookieValidationResult?,
    cookieProbe: CookieProbe,
    onRegionSelected: (Boolean) -> Unit,
    onCookiesExtracted: (CookieValidationResult) -> Unit,
    onCookieDiagnostic: (CookieValidationResult) -> Unit,
    onForceExtract: () -> Unit
) {
    var showAlert by rememberSaveable(showSessionExpiredAlert) {
        mutableStateOf(showSessionExpiredAlert)
    }

    if (showAlert) {
        AlertDialog(
            onDismissRequest = { showAlert = false },
            title = { Text("提示") },
            text = { Text("登录失效，请重新登录") },
            confirmButton = {
                TextButton(onClick = { showAlert = false }) {
                    Text("确认")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Region Switcher
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "选择 iCloud 账号服务区域：",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = isChinaMainland,
                    onClick = { onRegionSelected(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text("中国大陆 (云上贵州)")
                }
                SegmentedButton(
                    selected = !isChinaMainland,
                    onClick = { onRegionSelected(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text("国际区 (icloud.com)")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "提示",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "提示：完成 2FA 验证后，请务必勾选‘信任此浏览器’，以便保存长效凭证。登录完成后请在下方折叠菜单中点击“提取 cookie”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        // Live cookie diagnostics (shown only while auto-detection has not yet succeeded)
        if (diagnostics != null) {
            CookieDiagnosticsCard(
                result = diagnostics,
                onForceExtract = onForceExtract
            )
        }

        // WebView Area
        Box(modifier = Modifier.weight(1f)) {
            ICloudWebView(
                isChinaMainland = isChinaMainland,
                onCookiesExtracted = onCookiesExtracted,
                onCookieDiagnostic = onCookieDiagnostic,
                probe = cookieProbe
            )
        }
    }
}

@Composable
private fun CookieDiagnosticsCard(
    result: CookieValidationResult,
    onForceExtract: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Cookie 提取诊断 (已捕获 ${result.cookieNames.size} 项)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f)
                )

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "折叠" else "展开",
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                ) {
                    CookieStatusRow(
                        name = COOKIE_AUTH_TOKEN,
                        present = result.hasAuthToken,
                        length = result.valueLength(COOKIE_AUTH_TOKEN)
                    )
                    CookieStatusRow(
                        name = COOKIE_HSA_TRUST,
                        present = result.hasHsaTrust,
                        length = result.valueLength(COOKIE_HSA_TRUST)
                    )
                    CookieStatusRow(
                        name = COOKIE_PCS_CLOUDKIT,
                        present = result.hasPcsCloudKit,
                        length = result.valueLength(COOKIE_PCS_CLOUDKIT)
                    )

                    if (!result.expiresSummary.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "提示: ${result.expiresSummary}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = if (result.missingKeys.isEmpty()) {
                            "三要素齐全，正在提交 Go 核心层…"
                        } else {
                            "缺少: ${result.missingKeys.joinToString(", ")}" +
                                if (!result.hasHsaTrust) "（请确认登录时勾选了“信任此浏览器”）" else ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(onClick = onForceExtract) {
                        Text("提取 Cookie")
                    }
                }
            }
        }
    }
}

@Composable
private fun CookieStatusRow(name: String, present: Boolean, length: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Icon(
            imageVector = if (present) Icons.Default.CheckCircle else Icons.Default.Close,
            contentDescription = null,
            tint = if (present) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (present) "$name  (len=$length)" else "$name  未捕获",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

@Composable
private fun AuthenticatingContent(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun LoggedInContent(
    state: AuthUiState.LoggedIn,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "已登录",
                        tint = Color(0xFF4CAF50)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "iCloud 账号已就绪",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                InfoRow(label = "节点服务", value = if (state.isChinaMainland) "中国大陆 (云上贵州)" else "国际区")
                InfoRow(label = "账号 DSID", value = state.dsid)
                InfoRow(label = "CloudKit 动态路由", value = state.ckDatabaseUrl)
                InfoRow(
                    label = "2FA HSA-TRUST 凭证",
                    value = if (state.hasHsaTrust) "已捕获 (长效静默续期)" else "未检测到 (仍可正常预览)"
                )

                // Debug inspect expander
                val accountInfo = remember {
                    try {
                        com.getup.icloudcore.Icloudcore.getAccountInfoJSON()
                    } catch (e: Exception) {
                        "{}"
                    }
                }
                if (accountInfo != "{}" && accountInfo.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Debug Session 摘要: ${com.getup.icloudcore.Icloudcore.getSessionStateSummary()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedButton(
            onClick = onLogout,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("退出登录并清除本地凭证")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ErrorContent(
    errorMessage: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "错误",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .width(48.dp)
                    .height(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "鉴权或路由解析失败",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry) {
                Text("重新加载登录页")
            }
        }
    }
}
