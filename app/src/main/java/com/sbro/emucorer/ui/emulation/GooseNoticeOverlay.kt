package com.sbro.emucorer.ui.emulation

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sbro.emucorer.core.NativeCoreBridge
import kotlinx.coroutines.delay

private data class GooseScreenNotice(val text: String, val until: Long)

@Composable
internal fun GooseNoticeOverlay(modifier: Modifier = Modifier) {
    val bridge = remember { NativeCoreBridge() }
    var notices by remember { mutableStateOf(emptyList<GooseScreenNotice>()) }
    LaunchedEffect(bridge) {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            var next = notices.filter { it.until > now }
            // Drain a bounded queue without ever running the core on the UI thread.
            for (i in 0 until 32) {
                val raw = bridge.pollCoreNotice() ?: break
                val split = raw.indexOf('\n')
                if (split < 0) continue
                val duration = raw.substring(0, split).toLongOrNull() ?: continue
                val text = raw.substring(split + 1)
                next = (next.filterNot { it.text == text } +
                    GooseScreenNotice(text, now + duration)).takeLast(3)
            }
            notices = next
            delay(100)
        }
    }
    if (notices.isNotEmpty()) {
        Column(modifier = modifier.widthIn(max = 600.dp)
            .background(Color.Black.copy(alpha = 0.78f)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            notices.forEach { Text(it.text, color = Color.White, fontSize = 14.sp) }
        }
    }
}
