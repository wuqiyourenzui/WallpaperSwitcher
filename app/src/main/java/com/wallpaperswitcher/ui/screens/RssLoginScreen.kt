package com.wallpaperswitcher.ui.screens

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallpaperswitcher.R
import com.wallpaperswitcher.engine.RssCookieStore
import com.wallpaperswitcher.ui.theme.HiLoadingState
import com.wallpaperswitcher.viewmodel.WallpaperViewModel
import kotlinx.coroutines.launch

/**
 * Interactive login for a subscription source: the source's `loginUrl` (or
 * the source itself) opens in a real WebView, the user signs in, and tapping
 * 完成登录 copies the WebView cookies into the persistent cookie jar so every
 * later fetch is authenticated.
 */
@Composable
fun RssLoginScreen(
    viewModel: WallpaperViewModel,
    sourceId: Long,
    onDone: () -> Unit,
) {
    val sources by viewModel.rssSources.collectAsStateWithLifecycle()
    val source = sources.firstOrNull { it.id == sourceId }
    var loginUrl by remember(sourceId) { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var autoChecked by remember(sourceId) { mutableStateOf(false) }
    val currentSource by rememberUpdatedState(source)
    val isScriptLogin = source?.let { viewModel.rssLoginIsScript(it) } ?: false
    val scriptFields = remember(sourceId, source?.rawJson) {
        source?.let { viewModel.rssLoginFields(it) }.orEmpty()
    }
    val scriptValues = remember(sourceId, source?.rawJson) {
        mutableStateMapOf<String, String>().apply {
            source?.let { viewModel.rssLoginSavedValues(it) }?.forEach { (k, v) -> put(k, v) }
            for ((name, _) in scriptFields) if (!containsKey(name)) put(name, "")
        }
    }
    var scriptError by remember(sourceId) { mutableStateOf<String?>(null) }
    var scriptRunning by remember(sourceId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /** Copy the WebView cookies into the persistent jar and refresh the source. */
    fun finishLogin(current: com.wallpaperswitcher.data.RssSource?, url: String?) {
        if (url != null) {
            val cookie = try {
                CookieManager.getInstance().getCookie(url)
            } catch (_: Throwable) {
                null
            }
            RssCookieStore.injectCookieHeader(url, cookie)
        }
        if (current != null) viewModel.rssLoginCompleted(current)
        onDone()
    }

    LaunchedEffect(sourceId, source?.rawJson) {
        val current = source ?: return@LaunchedEffect
        loginUrl = viewModel.rssLoginEndpoint(current)
    }
    DisposableEffect(Unit) {
        onDispose {
            try {
                webView?.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            stringResource(
                if (isScriptLogin) R.string.rss_login_form_hint else R.string.rss_login_hint
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
        val url = loginUrl
        if (isScriptLogin) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                for ((name, type) in scriptFields) {
                    OutlinedTextField(
                        value = scriptValues[name].orEmpty(),
                        onValueChange = { scriptValues[name] = it },
                        label = { Text(name) },
                        singleLine = true,
                        visualTransformation = if (type.equals("password", true)) {
                            PasswordVisualTransformation()
                        } else {
                            androidx.compose.ui.text.input.VisualTransformation.None
                        },
                        keyboardOptions = if (type.equals("password", true)) {
                            KeyboardOptions(keyboardType = KeyboardType.Password)
                        } else {
                            KeyboardOptions.Default
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    )
                }
                scriptError?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
            }
        } else if (url == null) {
            HiLoadingState(
                text = stringResource(R.string.online_picker_loading),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, finishedUrl: String) {
                                // 阅读 `loginCheckJs`: when the page itself
                                // reports an authenticated session (e.g. the
                                // user was already signed in), finish at once.
                                val check = currentSource?.let { viewModel.rssLoginCheckJs(it) }
                                if (check.isNullOrBlank() || autoChecked) return
                                val script = if (check.contains("return")) {
                                    "(function(){ $check })()"
                                } else {
                                    "(function(){ return ($check) })()"
                                }
                                try {
                                    view.evaluateJavascript(script) { result ->
                                        val text = result?.trim()?.removeSurrounding("\"")
                                        val loggedIn = !text.isNullOrBlank() &&
                                            text != "null" && text != "false" &&
                                            text != "undefined" && text != "0"
                                        if (loggedIn && !autoChecked) {
                                            autoChecked = true
                                            finishLogin(currentSource, finishedUrl)
                                        }
                                    }
                                } catch (_: Throwable) {
                                }
                            }
                        }
                        loadUrl(url)
                        webView = this
                    }
                }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDone) {
                Text(stringResource(R.string.action_cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                enabled = !scriptRunning,
                onClick = {
                    val current = source
                    if (!isScriptLogin || current == null) {
                        finishLogin(current, loginUrl)
                        return@Button
                    }
                    scriptRunning = true
                    scriptError = null
                    scope.launch {
                        val error = viewModel.rssLoginRunScript(current, scriptValues.toMap())
                        scriptRunning = false
                        if (error == null) {
                            onDone()
                        } else {
                            scriptError = error
                        }
                    }
                }
            ) {
                Text(
                    if (isScriptLogin) stringResource(R.string.rss_login_submit)
                    else stringResource(R.string.rss_login_finish)
                )
            }
        }
    }
}
