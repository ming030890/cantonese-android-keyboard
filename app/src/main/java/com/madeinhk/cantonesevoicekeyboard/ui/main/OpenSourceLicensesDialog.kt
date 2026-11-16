package com.madeinhk.cantonesevoicekeyboard.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madeinhk.cantonesevoicekeyboard.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class OpenSourceNoticeSection(
    val title: String,
    val assetPath: String,
)

internal val OPEN_SOURCE_NOTICE_SECTIONS = listOf(
    OpenSourceNoticeSection(
        title = "Qwen3-ASR-1.7B-Cantonese-HK (Open-Source ASR Model — joe2643 / Alibaba Cloud Qwen Team)",
        assetPath = "licenses/Qwen3-ASR-Cantonese-HK.txt",
    ),
    OpenSourceNoticeSection(
        title = "QwenASR (On-Device ASR Inference Engine)",
        assetPath = "licenses/QwenASR.txt",
    ),
    OpenSourceNoticeSection(
        title = "Silero VAD (Voice Activity Detection Model)",
        assetPath = "licenses/Silero-VAD.txt",
    ),
    OpenSourceNoticeSection(
        title = "ARM KleidiAI",
        assetPath = "licenses/KleidiAI.txt",
    ),
    OpenSourceNoticeSection(
        title = "OpenBLAS",
        assetPath = "licenses/OpenBLAS.txt",
    ),
    OpenSourceNoticeSection(
        title = "SCOWL English Word List",
        assetPath = "licenses/SCOWL.txt",
    ),
    OpenSourceNoticeSection(
        title = "LibreOffice British English Dictionary — Derivation Notice",
        assetPath = "licenses/english_dictionary/DERIVATION.txt",
    ),
    OpenSourceNoticeSection(
        title = "LibreOffice British English Dictionary — Upstream Attribution",
        assetPath = "licenses/english_dictionary/README_en_GB.txt",
    ),
    OpenSourceNoticeSection(
        title = "GNU Lesser General Public License v2.1",
        assetPath = "licenses/english_dictionary/LGPL-2.1.txt",
    ),
    OpenSourceNoticeSection(
        title = "GNU General Public License v2.0",
        assetPath = "licenses/english_dictionary/GPL-2.0.txt",
    ),
    OpenSourceNoticeSection(
        title = "Unicode Emoji Data",
        assetPath = "emoji/UNICODE-LICENSE.txt",
    ),
    OpenSourceNoticeSection(
        title = "AndroidX Libraries",
        assetPath = "emoji/ANDROIDX-LICENSE.txt",
    ),
)

@Composable
internal fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    val assets = LocalContext.current.applicationContext.assets
    var notices by remember { mutableStateOf("") }
    LaunchedEffect(assets) {
        notices = withContext(Dispatchers.IO) {
            OPEN_SOURCE_NOTICE_SECTIONS.joinToString("\n\n────────────────────────────────────────\n\n") { section ->
                val text = assets.open(section.assetPath).bufferedReader().use { it.readText().trim() }
                "${section.title}\n\n$text"
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBackground,
        titleContentColor = PrimaryTextColor,
        textContentColor = SecondaryTextColor,
        title = {
            Text(
                text = stringResource(R.string.open_source_licenses),
                fontWeight = FontWeight.Bold,
                color = PrimaryTextColor,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MintAccentColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, MintAccentColor.copy(alpha = 0.35f)),
                ) {
                    Text(
                        text = stringResource(R.string.open_source_asr_summary),
                        color = PrimaryTextColor,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = CardBorderColor)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = notices,
                    color = SecondaryTextColor,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.close),
                    color = MintAccentColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    )
}
