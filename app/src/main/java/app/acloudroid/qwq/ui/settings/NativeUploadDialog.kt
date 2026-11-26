package app.acloudroid.qwq.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.acloudroid.qwq.R
import app.acloudroid.qwq.data.session.SessionManager
import app.acloudroid.qwq.feature.photos.data.NativeUploadRepository
import app.acloudroid.qwq.feature.photos.data.NativeUploadRepository.LoginStep
import kotlinx.coroutines.launch

/**
 * "大文件上传" sign-in. A plain dialog is enough here: it holds no shared-element content and the
 * settings overlay already owns back handling (AGENTS §10.0).
 */
@Composable
fun NativeUploadDialog(
    repository: NativeUploadRepository,
    onDismiss: () -> Unit
) {
    val status by repository.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repository.refreshStatus() } }

    var anisetteUrl by remember(status.anisetteUrl) { mutableStateOf(status.anisetteUrl) }
    var appleId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var needCode by remember { mutableStateOf(false) }
    var sms by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val checkOk = stringResource(R.string.native_upload_check_ok)
    val uriHandler = LocalUriHandler.current
    val typedIsPublic = SessionManager.isPublicAnisetteUrl(anisetteUrl)
    val consentMissing = typedIsPublic && !status.publicConsent

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    fun apply(step: LoginStep) {
        when (step) {
            is LoginStep.Done -> {
                needCode = false; password = ""; code = ""
            }
            is LoginStep.NeedCode -> {
                needCode = true; sms = step.sms; password = ""
            }
            is LoginStep.Failed -> {
                needCode = step.stillNeedsCode; sms = step.sms
                message = "${step.errorCode}: ${step.message}"
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (needCode) repository.cancelLogin()
            password = ""
            onDismiss()
        },
        title = { Text(stringResource(R.string.settings_item_native_upload)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.native_upload_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = anisetteUrl,
                    onValueChange = { anisetteUrl = it },
                    label = { Text(stringResource(R.string.native_upload_anisette)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                if (anisetteUrl.trim() != status.anisetteUrl) {
                    TextButton(onClick = { run { repository.setAnisetteUrl(anisetteUrl) } }) {
                        Text(stringResource(R.string.native_upload_save_url))
                    }
                } else if (anisetteUrl.trim() != SessionManager.DEFAULT_ANISETTE_URL && !status.loggedIn) {
                    TextButton(enabled = !busy, onClick = {
                        anisetteUrl = SessionManager.DEFAULT_ANISETTE_URL
                        run { repository.setAnisetteUrl(SessionManager.DEFAULT_ANISETTE_URL) }
                    }) { Text(stringResource(R.string.native_upload_reset_url)) }
                }

                if (typedIsPublic) {
                    PublicServerDisclaimer(
                        accepted = status.publicConsent,
                        enabled = !busy,
                        onAcceptedChange = { repository.acceptPublicDisclaimer(it) }
                    )
                }
                TextButton(onClick = { uriHandler.openUri(SELF_HOST_GUIDE_URL) }) {
                    Text(stringResource(R.string.native_upload_self_host_link))
                }

                when {
                    status.loggedIn && !needCode -> {
                        Text(
                            stringResource(R.string.native_upload_logged_in, status.appleId.orEmpty()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = !busy, onClick = {
                                run {
                                    message = repository.check().fold(
                                        onSuccess = { checkOk },
                                        onFailure = { it.message }
                                    )
                                }
                            }) { Text(stringResource(R.string.native_upload_check)) }
                            TextButton(enabled = !busy, onClick = { run { repository.logout() } }) {
                                Text(stringResource(R.string.native_upload_logout), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    needCode -> {
                        Text(
                            stringResource(if (sms) R.string.native_upload_code_hint_sms else R.string.native_upload_code_hint_device),
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = code,
                            onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                            label = { Text(stringResource(R.string.native_upload_code)) },
                            singleLine = true,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (!sms) {
                            TextButton(enabled = !busy, onClick = { run { apply(repository.useSms()) } }) {
                                Text(stringResource(R.string.native_upload_use_sms))
                            }
                        }
                    }
                    else -> {
                        OutlinedTextField(
                            value = appleId,
                            onValueChange = { appleId = it },
                            label = { Text(stringResource(R.string.native_upload_apple_id)) },
                            singleLine = true,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.native_upload_password)) },
                            singleLine = true,
                            enabled = !busy,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (busy) {
                    Spacer(Modifier.height(4.dp))
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when {
                needCode -> TextButton(
                    enabled = !busy && code.length == 6,
                    onClick = { run { apply(repository.submitCode(code)) } }
                ) { Text(stringResource(R.string.native_upload_verify)) }
                !status.loggedIn -> TextButton(
                    enabled = !busy && !consentMissing && appleId.isNotBlank() && password.isNotEmpty(),
                    onClick = {
                        val pw = password
                        run {
                            if (anisetteUrl.trim() != status.anisetteUrl) repository.setAnisetteUrl(anisetteUrl)
                            apply(repository.beginLogin(appleId, pw))
                        }
                    }
                ) { Text(stringResource(R.string.native_upload_login)) }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
        dismissButton = {
            if (needCode || !status.loggedIn) {
                TextButton(onClick = {
                    if (needCode) repository.cancelLogin()
                    password = ""
                    onDismiss()
                }) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    )
}

/** How to run the fingerprint server on your own Mac or Windows PC. */
private const val SELF_HOST_GUIDE_URL =
    "https://github.com/CuteCatGYH/AClouDroid/blob/main/docs/ANISETTE_SERVER.md"

/**
 * Shown while the fingerprint URL points at a third-party public server. Login stays disabled
 * until the user ticks the box.
 */
@Composable
private fun PublicServerDisclaimer(
    accepted: Boolean,
    enabled: Boolean,
    onAcceptedChange: (Boolean) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(R.string.native_upload_public_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                stringResource(R.string.native_upload_public_body),
                style = MaterialTheme.typography.bodySmall
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = accepted, enabled = enabled, onCheckedChange = onAcceptedChange)
                Text(
                    stringResource(R.string.native_upload_public_accept),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
