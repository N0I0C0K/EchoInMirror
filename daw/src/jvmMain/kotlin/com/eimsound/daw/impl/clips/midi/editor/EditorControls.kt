package com.eimsound.daw.impl.clips.midi.editor

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eimsound.daw.actions.doNoteDisabledAction
import com.eimsound.daw.actions.doNoteVelocityAction
import com.eimsound.daw.api.EchoInMirror
import com.eimsound.daw.api.processor.Track
import com.eimsound.daw.components.CustomTextField
import com.eimsound.daw.components.FloatingLayer
import com.eimsound.daw.components.MenuItem
import com.eimsound.daw.components.TIMELINE_HEIGHT
import com.eimsound.daw.components.silder.Slider
import com.eimsound.daw.components.utils.toOnSurfaceColor
import com.eimsound.daw.utils.IManualStateValue
import com.eimsound.daw.window.panels.playlist.noteWidthSliderRange
import java.util.*
import kotlin.math.roundToInt

private fun dfsTrackIndex(track: Track, target: Track, index: String): String? {
    if (track == target) return index
    track.subTracks.forEachIndexed { i, child ->
        val res = dfsTrackIndex(child, target, "$index.${i + 1}")
        if (res != null) return res
    }
    return null
}

@Composable
private fun TrackItem(track: Track, backingTracks: IManualStateValue<WeakHashMap<Track, Unit>>, index: String, depth: Int = 0) {
    val isSelected = EchoInMirror.selectedClip?.clip == track
    MenuItem({
        if (backingTracks.value.remove(track) == null) backingTracks.value[track] = Unit
        backingTracks.update()
    }, isSelected || backingTracks.readValue().contains(track),
        minHeight = 28.dp, padding = PaddingValues(), modifier = Modifier.height(IntrinsicSize.Min)
    ) {
        Spacer(Modifier.width(6.dp * depth))
        Spacer(Modifier.width(6.dp).fillMaxHeight().background(track.color))
        Text(index + " " + track.name, Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp), maxLines = 1,
            style = MaterialTheme.typography.labelLarge, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
    }
    track.subTracks.forEachIndexed { i, it -> TrackItem(it, backingTracks, index + "." + (i + 1), depth + 1) }
}

@Composable
internal fun EditorControls(editor: DefaultMidiClipEditor) {
    val track = EchoInMirror.selectedTrack
    FloatingLayer({ size, _ ->
        Surface(Modifier.width(IntrinsicSize.Max).widthIn(min = size.width.dp), shape = MaterialTheme.shapes.extraSmall,
            tonalElevation = 5.dp, shadowElevation = 5.dp) {
            val stateVertical = rememberScrollState(0)
            Column(Modifier.verticalScroll(stateVertical).padding(vertical = 4.dp)) {
                EchoInMirror.bus!!.subTracks.forEachIndexed { index, it -> TrackItem(it, editor.backingTracks, (index + 1).toString()) }
            }
        }
    }) {
        val color by animateColorAsState(track?.color ?: MaterialTheme.colorScheme.surface, tween(100))
        Surface(Modifier.fillMaxWidth().height(TIMELINE_HEIGHT), shadowElevation = 2.dp, tonalElevation = 4.dp, color = color) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val textColor by animateColorAsState(color.toOnSurfaceColor(), tween(80))
                Text(
                    remember(track) {
                        if (track == null) null else dfsTrackIndex(EchoInMirror.bus!!, track, "")?.trimStart('.')
                    } ?: "?", Modifier.padding(horizontal = 8.dp),
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = MaterialTheme.typography.labelLarge.fontSize
                )
                Text(track?.name ?: "未选择", Modifier.weight(1f), textColor, style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Filled.ExpandMore, null, Modifier.padding(horizontal = 8.dp), textColor)
            }
        }
    }
    Column(Modifier.padding(10.dp)) {
        Slider(editor.noteWidth.value.value / 0.4f, { editor.noteWidth.value = 0.4.dp * it }, valueRange = noteWidthSliderRange)

        editor.apply {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("试听音符", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Checkbox(DefaultMidiClipEditor.playOnEdit, { DefaultMidiClipEditor.playOnEdit = !DefaultMidiClipEditor.playOnEdit })
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                editor.clip.clip.notes.read()
                var delta by remember { mutableStateOf(0) }
                val cur = currentSelectedNote
                val velocity = if (editor.selectedNotes.isEmpty()) DefaultMidiClipEditor.defaultVelocity else
                        (cur ?: editor.selectedNotes.first()).velocity
                val trueValue = velocity + (if (editor.selectedNotes.isEmpty()) 0 else delta)
                CustomTextField(trueValue.toString(), { str ->
                    val v = str.toIntOrNull()?.coerceIn(0, 127) ?: return@CustomTextField
                    if (editor.selectedNotes.isEmpty()) DefaultMidiClipEditor.defaultVelocity = v
                    else editor.clip.clip.doNoteVelocityAction(editor.selectedNotes.toTypedArray(), v - velocity)
                }, Modifier.width(60.dp).padding(end = 10.dp), label = { Text("力度") })
                Slider(trueValue.toFloat() / 127,
                    {
                        if (editor.selectedNotes.isEmpty()) DefaultMidiClipEditor.defaultVelocity = (it * 127).roundToInt()
                        else delta = (it * 127).roundToInt() - velocity
                    }, Modifier.weight(1f), onValueChangeFinished = {
                        if (editor.selectedNotes.isNotEmpty()) editor.clip.clip
                            .doNoteVelocityAction(editor.selectedNotes.toTypedArray(), delta)
                        delta = 0
                    }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                editor.clip.clip.notes.read()
                Text("禁用", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                val cur = currentSelectedNote ?: editor.selectedNotes.firstOrNull()
                val curState = cur?.disabled ?: true
                Checkbox(curState, {
                    if (cur == null) return@Checkbox
                    editor.clip.clip.doNoteDisabledAction(editor.selectedNotes.toTypedArray(), !curState)
                })
            }
        }
    }
}
