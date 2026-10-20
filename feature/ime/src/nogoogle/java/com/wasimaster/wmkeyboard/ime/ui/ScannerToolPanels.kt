package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R

/**
 * F-Droid stand-ins for the ML Kit scanner panels. The OCR and QR tools
 * are hidden in that build, so these are unreachable; they exist so
 * KeyboardScreen's panel switch keeps compiling.
 */
@Composable
internal fun OcrPanel(
    state: KeyboardUiState,
    onInsert: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onClose: () -> Unit,
    onTranslate: (String) -> Unit = {},
) = UnavailablePanel(state, stringResource(R.string.ime_scanner_ocr_unavailable_info))

@Composable
internal fun QrScanPanel(
    state: KeyboardUiState,
    onInsert: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onClose: () -> Unit,
) = UnavailablePanel(state, stringResource(R.string.ime_scanner_qr_unavailable_info))

@Composable
private fun UnavailablePanel(state: KeyboardUiState, message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(keyRowsHeight(state)),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
