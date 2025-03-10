package com.eimsound.daw.window.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eimsound.daw.components.ColorPicker
import com.eimsound.daw.components.FloatingLayerProvider
import com.eimsound.daw.components.Dialog
import com.eimsound.daw.components.utils.HsvColor
import com.eimsound.daw.components.utils.randomColor

private val KEY = Any()

fun FloatingLayerProvider.openColorPicker(initialColor: Color = randomColor(),
                                          onCancel: (() -> Unit)? = null, onClose: (Color) -> Unit) {
    openFloatingLayer({
        closeFloatingLayer(KEY)
        onCancel?.invoke()
    }, key = KEY, hasOverlay = true) {
        Dialog {
            var currentColor by remember { mutableStateOf(HsvColor.from(initialColor)) }
            ColorPicker(currentColor, Modifier.size(200.dp)) { currentColor = it }
            Row(Modifier.fillMaxWidth().padding(end = 10.dp),
                horizontalArrangement = Arrangement.End) {
                TextButton({
                    closeFloatingLayer(KEY)
                    onCancel?.invoke()
                }) { Text("取消") }
                TextButton({
                    closeFloatingLayer(KEY)
                    onClose(currentColor.toColor())
                }) { Text("确认") }
            }
        }
    }
}
