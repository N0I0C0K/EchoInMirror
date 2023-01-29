package com.eimsound.daw.impl.clips.midi.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.eimsound.audioprocessor.data.midi.NoteMessage
import com.eimsound.daw.EchoInMirror
import com.eimsound.daw.actions.doNoteVelocityAction
import com.eimsound.daw.api.MidiCCEvent
import com.eimsound.daw.api.MidiClip
import com.eimsound.daw.api.TrackClip
import com.eimsound.daw.components.EditorGrid
import com.eimsound.daw.components.EnvelopeEditor
import com.eimsound.daw.components.KEYBOARD_DEFAULT_WIDTH
import com.eimsound.daw.components.utils.Stroke1_5PX
import com.eimsound.daw.utils.clickableWithIcon
import com.eimsound.daw.window.panels.playlist.EditAction
import kotlin.math.roundToInt

interface EventType {
    val range: IntRange
    val name: String
    val isInteger: Boolean
    @Composable
    fun Editor(clip: TrackClip<MidiClip>)
}

object VelocityEvent : EventType {
    override val range = 0..127
    override val name = "力度"
    override val isInteger = true
    @Composable
    override fun Editor(clip: TrackClip<MidiClip>) {
        val primaryColor = MaterialTheme.colorScheme.primary
        var delta by remember { mutableStateOf(0) }
        var selectedNote by remember { mutableStateOf<NoteMessage?>(null) }
        Spacer(Modifier.fillMaxSize().drawBehind {
            val track = EchoInMirror.selectedTrack ?: return@drawBehind
            if (size.height < 1) return@drawBehind
            val noteWidthPx = noteWidth.value.toPx()
            val offsetOfDelta = -delta / 127F * size.height
            val offsetX = if (action == EditAction.MOVE) deltaX * noteWidthPx else 0f
            val scrollX = horizontalScrollState.value
            val startTime = clip.time
            notesInView.forEach {
                val isSelected = selectedNotes.contains(it)
                val x = (startTime + it.time) * noteWidthPx - scrollX + 2 + (if (isSelected) offsetX else 0f)
                val y = size.height * (1 - it.velocity / 127F) + (if (isSelected || selectedNote == it) offsetOfDelta else 0f)
                drawLine(track.color, Offset(x, y.coerceIn(0f, size.height - 1)), Offset(x, size.height), 4f)
            }
            selectedNotes.forEach {
                val x = (startTime + it.time) * noteWidthPx - scrollX + offsetX
                val y = (size.height * (1 - it.velocity / 127F) + offsetOfDelta).coerceIn(0f, size.height - 1)
                drawRect(primaryColor, Offset(x, y),
                    Size(4f, size.height - y), style = Stroke1_5PX)
            }
        }.pointerInput(clip) {
            detectDragGestures({
                val x = it.x + horizontalScrollState.value
                val noteWidthPx = noteWidth.value.toPx()
                val startTime = clip.time
                for (i in startNoteIndex until clip.clip.notes.size) {
                    val note = clip.clip.notes[i]
                    val curX = (startTime + note.time) * noteWidthPx
                    if (curX <= x && x <= curX + 4) {
                        if (selectedNotes.isNotEmpty() && !selectedNotes.contains(note)) continue
                        selectedNote = note
                        break
                    }
                }
            }, {
                val cur = selectedNote
                if (cur != null) {
                    selectedNote = null
                    clip.clip.doNoteVelocityAction(
                        if (selectedNotes.isEmpty()) arrayOf(cur) else selectedNotes.toTypedArray(), delta)
                }
                delta = 0
            }) { it, _ ->
                val cur = selectedNote ?: return@detectDragGestures
                delta = ((1 - it.position.y / size.height) * 127 - cur.velocity).roundToInt().coerceIn(-127, 127)
            }
        })
    }
}

data class CCEvent(val event: MidiCCEvent) : EventType {
    override val range = 0..127
    override val name = "CC:${event.id}"
    override val isInteger = true
    @Composable
    override fun Editor(clip: TrackClip<MidiClip>) {
        EnvelopeEditor(event.points,
            clip.start + clip.time + with (LocalDensity.current) { horizontalScrollState.value / noteWidth.value.toPx() },
            clip.track?.color ?: MaterialTheme.colorScheme.primary,
            range,
            noteWidth
        )
    }
}

var selectedEvent: EventType by mutableStateOf(VelocityEvent)

@OptIn(ExperimentalTextApi::class)
@Composable
internal fun EventEditor(clip: TrackClip<MidiClip>) {
    Column(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxWidth().zIndex(2f), shadowElevation = 5.dp) {
            Row {
                Text("力度", Modifier.clickableWithIcon {
                    selectedEvent = VelocityEvent
                }.padding(4.dp, 2.dp), style = MaterialTheme.typography.labelLarge)
                clip.clip.events.forEach {
                    Text("CC:${it.id}", Modifier.clickableWithIcon {
                        selectedEvent = CCEvent(it)
                    }.padding(4.dp, 2.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Row(Modifier.fillMaxSize()) {
            Surface(Modifier.width(KEYBOARD_DEFAULT_WIDTH).fillMaxHeight().zIndex(2f), shadowElevation = 5.dp) {
                val lineColor = MaterialTheme.colorScheme.outlineVariant
                val style = MaterialTheme.typography.labelMedium
                val measurer = rememberTextMeasurer()
                Canvas(Modifier.fillMaxSize()) {
                    val lines = (size.height / 50).toInt().coerceAtMost(5)
                    val lineSize = size.height / lines
                    val isInteger = selectedEvent.isInteger
                    val last = selectedEvent.range.last
                    val lastF = last.toFloat()
                    for (i in 1..lines) {
                        drawLine(
                            lineColor,
                            Offset(size.width - 10, lineSize * i),
                            Offset(size.width, lineSize * i),
                            1F
                        )
                        val value = (lastF / lines) * (lines - i)
                        val result = measurer.measure(AnnotatedString(if (isInteger) value.roundToInt().toString() else "%.2F".format(value)), style)
                        drawText(result, lineColor,
                            Offset(size.width - 14 - result.size.width, (lineSize * i - result.size.height / 2)
                                .coerceAtMost(size.height - result.size.height)))
                    }
                    val result = measurer.measure(AnnotatedString(if (isInteger) last.toString() else "$last.00"), style)
                    drawText(result, lineColor, Offset(size.width - 14 - result.size.width, 0F))
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
                EditorGrid(noteWidth, horizontalScrollState)
                selectedEvent.Editor(clip)
            }
        }
    }
}
