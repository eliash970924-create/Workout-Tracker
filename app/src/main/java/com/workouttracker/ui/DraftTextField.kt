package com.workouttracker.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/**
 * A text field that owns what you are typing.
 *
 * Binding a field's `value` straight to something that round-trips through the
 * database races every keystroke. The write is asynchronous, so between the
 * keystroke and the database catching up the field recomposes with the previous
 * text; the cursor is then placed by index into a string that just got shorter,
 * which lands it in front of the character you just typed. Type faster than the
 * round trip and keystrokes are overwritten outright -- a lost space being the
 * easiest one to notice.
 *
 * So the buffer lives here while the field has focus, and [value] is only taken
 * back once it does not. An edit arriving from elsewhere -- a sync, a reload --
 * still shows up, just not in the middle of a word.
 */
@Composable
fun DraftTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    /** Applied before the text is shown or reported, e.g. digits only. */
    transform: (String) -> String = { it },
) {
    var draft by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(value, focused) {
        if (!focused && draft != value) draft = value
    }

    OutlinedTextField(
        value = draft,
        onValueChange = { raw ->
            val cleaned = transform(raw)
            draft = cleaned
            onValueChange(cleaned)
        },
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = keyboardOptions,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
    )
}
