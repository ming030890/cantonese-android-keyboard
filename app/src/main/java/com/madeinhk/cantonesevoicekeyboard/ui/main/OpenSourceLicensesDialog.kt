package com.madeinhk.cantonesevoicekeyboard.ui.main

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import com.madeinhk.cantonesevoicekeyboard.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    val assets = LocalContext.current.applicationContext.assets
    var notices by remember { mutableStateOf("") }
    LaunchedEffect(assets) {
        notices = withContext(Dispatchers.IO) {
            listOf(
                "emoji/ANDROIDX-LICENSE.txt",
                "emoji/UNICODE-LICENSE.txt",
                "licenses/SCOWL.txt",
                "licenses/QwenASR.txt",
                "licenses/Qwen3-ASR-Cantonese-HK.txt",
                "licenses/OpenBLAS.txt",
                "licenses/KleidiAI.txt",
                "licenses/english_dictionary/DERIVATION.txt",
                "licenses/english_dictionary/README_en_GB.txt",
                "licenses/english_dictionary/LGPL-2.1.txt",
                "licenses/english_dictionary/GPL-2.0.txt",
            ).joinToString("\n\n") { path ->
                assets.open(path).bufferedReader().use { it.readText() }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.open_source_licenses)) },
        text = { Text(notices, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
