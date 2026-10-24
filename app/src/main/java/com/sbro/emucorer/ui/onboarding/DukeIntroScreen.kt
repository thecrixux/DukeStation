package com.sbro.emucorer.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.sbro.emucorer.R

@Composable
internal fun DukeIntroScreen(page: Int, onNext: () -> Unit, onBack: () -> Unit) {
    BackHandler(enabled = page > 0, onBack = onBack)
    val gold = Color(0xFFFFB800)
    val image = ImageBitmap.imageResource(when (page) {
        0 -> R.drawable.duke_intro_welcome
        1 -> R.drawable.duke_intro_emulation
        else -> R.drawable.duke_intro_library
    })
    val title = stringResource(when (page) {
        0 -> R.string.onboarding_page_1_title
        1 -> R.string.onboarding_page_2_title
        else -> R.string.onboarding_page_3_title
    })
    val highlight = when (page) {
        0 -> "DukeStation"
        1 -> stringResource(R.string.duke_emulation_highlight)
        else -> stringResource(R.string.duke_library_highlight)
    }
    val styledTitle = buildAnnotatedString {
        append(title)
        val start = title.indexOf(highlight, ignoreCase = true)
        if (start >= 0) addStyle(SpanStyle(color = gold), start, start + highlight.length)
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF101012))) {
        val landscape = maxWidth > maxHeight
        // Show the supplied artwork in full, preserving its original proportions.
        Image(bitmap = image, contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)
            .heightIn(max = maxHeight * (if (landscape) 0.95f else 0.48f))
            .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(styledTitle, color = Color(0xFFF2F2F2),
                style = androidx.compose.ui.text.TextStyle(shadow = Shadow(
                    color = Color.Black, blurRadius = 10f)),
                fontSize = if (landscape) 30.sp else 34.sp,
                lineHeight = 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(4) { index ->
                    Box(Modifier.size(if (index == page) 26.dp else 9.dp, 9.dp)
                        .background(if (index == page) gold else Color(0xFF55555D), CircleShape))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                if (page > 0) TextButton(onClick = onBack) {
                    Text(stringResource(R.string.onboarding_back), color = Color(0xFFF2F2F2))
                } else Spacer(Modifier.width(48.dp))
                Button(onClick = onNext, colors = ButtonDefaults.buttonColors(
                    containerColor = gold, contentColor = Color(0xFF101012))) {
                    Text(stringResource(R.string.onboarding_next), modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
}
