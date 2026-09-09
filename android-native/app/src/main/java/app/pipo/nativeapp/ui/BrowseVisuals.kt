package app.pipo.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The frozen contract's smoky fields; normal text flow grows for font scaling. */
@Composable
internal fun BrowseSearchField(value: String, onValueChange: (String) -> Unit, hint: String, onSearch: (() -> Unit)? = null, glass: Boolean = false, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = BrowseInk, fontSize = 16.sp),
        cursorBrush = SolidColor(BrowseInk),
        keyboardOptions = KeyboardOptions(imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Done),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = hint },
        decorationBox = { field ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(PipoDimens.SurfaceCornerDp))
                    .background(if (glass) Color.Transparent else Color.White.copy(alpha = 0.035f))
                    .border(0.5.dp, Color.White.copy(alpha = if (glass) 0f else 0.12f), RoundedCornerShape(PipoDimens.SurfaceCornerDp))
                    .heightIn(min = 48.dp).padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchIcon(BrowseMuted, Modifier.size(22.dp))
                Box(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp)) {
                    if (value.isEmpty()) Text(hint, color = BrowseMuted, fontSize = 16.sp)
                    field()
                }
                if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }, modifier = Modifier.semantics { contentDescription = "清空" }) {
                    CloseIcon(BrowseMuted, Modifier.size(18.dp))
                }
            }
        },
    )
}
