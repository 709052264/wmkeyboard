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
 * F-Droid stand-in for the ML Kit text scanner panel. The OCR tool is hidden
 * in that build, so this is unreachable; it exists so KeyboardScreen's panel
 * switch keeps compiling. The QR scanner needs no stand-in: it lives in
 * src/main and reads with zxing-cpp here.
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
