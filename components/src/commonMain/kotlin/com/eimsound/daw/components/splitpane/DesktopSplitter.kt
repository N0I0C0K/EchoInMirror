package com.eimsound.daw.components.splitpane

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.eimsound.daw.components.utils.HorizontalResize
import com.eimsound.daw.components.utils.VerticalResize

@Composable
fun DefaultHandle(
    isHorizontal: Boolean,
    splitPaneState: SplitPaneState
) = Box(
    Modifier
        .pointerInput(splitPaneState) {
            detectDragGestures { change, _ ->
                change.consume()
                splitPaneState.dispatchRawMovement(
                    if (isHorizontal) change.position.x else change.position.y
                )
            }
        }
        .pointerHoverIcon(if (isHorizontal) PointerIcon.HorizontalResize else PointerIcon.VerticalResize)
        .run {
            if (isHorizontal) {
                this.width(8.dp)
                    .fillMaxHeight()
            } else {
                this.height(8.dp)
                    .fillMaxWidth()
            }
        }
)

/**
 * Internal implementation of default splitter
 *
 * @param isHorizontal describes is it horizontal or vertical split pane
 * @param splitPaneState the state object to be used to control or observe the split pane state
 */
internal fun defaultSplitter(
    isHorizontal: Boolean,
    splitPaneState: SplitPaneState
): Splitter = Splitter(
    measuredPart = {},
    handlePart = {
        DefaultHandle(isHorizontal, splitPaneState)
    }
)

