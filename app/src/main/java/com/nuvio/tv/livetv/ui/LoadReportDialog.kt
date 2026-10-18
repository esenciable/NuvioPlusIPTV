package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.model.LiveTvLoadReport
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Developer tool: what Live TV did while loading this time (saved copies used or not and why,
 * with timings), plus a QR code so a user can send it from their phone.
 */
@Composable
fun LoadReportDialog(onDismiss: () -> Unit) {
    val lines by LiveTvLoadReport.lines.collectAsStateWithLifecycle()
    val text = lines.joinToString("\n")
    val qr = remember(text) {
        runCatching { com.nuvio.tv.core.qr.QrCodeGenerator.generate(text.takeLast(1_700).ifBlank { "(empty)" }, 360) }.getOrNull()
    }
    LiveDialog(onDismiss = onDismiss, width = 860.dp) {
        LiveText("Live TV start-up report", size = 20.sp, weight = FontWeight.Bold)
        LiveText(
            "Since the app started. ▲ ▼ to scroll. Scan the code to send the latest part from your phone.",
            color = NuvioTheme.colors.TextSecondary, size = 13.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Scrolls with the remote (it couldn't be scrolled at all): opens at the newest lines,
            // ▲ goes back; ▼ at the end moves on to Close.
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val listFocus = remember { FocusRequester() }
            var listFocused by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
                delay(60); runCatching { listFocus.requestFocus() }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 380.dp)
                    .border(
                        if (listFocused) 2.dp else 1.dp,
                        if (listFocused) NuvioTheme.colors.FocusRing else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(6.dp)
                    .focusRequester(listFocus)
                    .onFocusChanged { listFocused = it.isFocused }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionUp -> {
                                if (!listState.canScrollBackward) return@onPreviewKeyEvent false
                                scope.launch { listState.scrollBy(-240f) }; true
                            }
                            Key.DirectionDown -> {
                                if (!listState.canScrollForward) return@onPreviewKeyEvent false
                                scope.launch { listState.scrollBy(240f) }; true
                            }
                            else -> false
                        }
                    }
                    .focusable()
            ) {
                if (lines.isEmpty()) item { LiveText("Nothing yet: open Live TV first.", size = 13.sp) }
                items(lines) { line ->
                    androidx.tv.material3.Text(
                        text = line, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = NuvioTheme.colors.TextPrimary
                    )
                }
            }
            qr?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(180.dp).background(Color.White).padding(6.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        MenuItem("Close", onClick = onDismiss)
    }
}
