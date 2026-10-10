package com.abrogani.superstream.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.abrogani.superstream.BuildConfig
import com.flixclusive.core.util.android.showToast
import com.flixclusive.provider.extensions.getIntAsFlow
import com.flixclusive.provider.extensions.remove
import com.flixclusive.provider.extensions.setInt
import com.flixclusive.provider.util.res.painterResource
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun getTokenStatusLabel(
    status: TokenStatus,
    color: Color
): AnnotatedString {
    return buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
            append("GOOGLE TOKEN STATUS: ")
        }

        withStyle(
            SpanStyle(
                fontWeight = FontWeight.Black,
                color = color
            )
        ) {
            append(status.toString())
        }
    }
}

@Composable
internal fun GetTokenScreen(
    settings: DataStore<Preferences>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenStatusIndex by remember(settings) {
        settings.getIntAsFlow(TOKEN_STATUS_KEY, 0)
    }.collectAsState(initial = 0)
    val tokenStatus = TokenStatus.entries.getOrElse(tokenStatusIndex) { TokenStatus.Offline }
    val color = remember(tokenStatus) {
        when (tokenStatus) {
            TokenStatus.Online -> Color(0xFF9FF375)
            else -> Color(0xFFFF6161)
        }
    }

    val webView = remember(context) {
        TokenGetterWebView(
            context = context,
            settings = settings,
            scope = scope,
            onTokenReceived = {
                context.showToast("Token has been set successfully!")
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = getTokenStatusLabel(
                status = tokenStatus,
                color = color
            ),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        )

        Box(
            modifier = Modifier
                .height(300.dp)
                .fillMaxWidth()
                .padding(10.dp)
                .border(2.dp, MaterialTheme.colorScheme.primary),
        ) {
            this@Column.AnimatedVisibility(
                visible = tokenStatus == TokenStatus.Offline,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                DisposableEffect(webView) {
                    onDispose {
                        webView.destroy()
                    }
                }

                val scrollableState = rememberScrollableState { delta ->
                    val scrollY = webView.scrollY
                    val consume = (scrollY - delta).coerceIn(0f, webView.verticalScrollRange.toFloat())
                    webView.scrollTo(0, consume.roundToInt())
                    (scrollY - webView.scrollY).toFloat()
                }

                AndroidView(
                    modifier = Modifier
                        .padding(5.dp)
                        .alpha(0.99F)
                        .scrollable(
                            state = scrollableState,
                            orientation = Orientation.Vertical
                        ),
                    factory = { webView }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ElevatedButton(
                onClick = {
                    if (tokenStatus == TokenStatus.Offline) {
                        context.showToast("ERR: Token is already offline!")
                        return@ElevatedButton
                    }

                    scope.launch {
                        settings.remove(TOKEN_KEY)
                        settings.setInt(TOKEN_STATUS_KEY, TokenStatus.Offline.ordinal)

                        context.showToast("Token has been reset!")
                    }
                },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .height(60.dp)
                    .weight(
                        weight = 1F,
                        fill = true
                    )
            ) {
                Text("Reset token")
            }

            ElevatedButton(
                onClick = webView::loadTokenUrl,
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .height(60.dp)
            ) {
                Icon(
                    painter = painterResource(
                        name = "refresh",
                        packageName = BuildConfig.LIBRARY_PACKAGE_NAME
                    ),
                    contentDescription = "Refresh"
                )
            }
        }
    }
}