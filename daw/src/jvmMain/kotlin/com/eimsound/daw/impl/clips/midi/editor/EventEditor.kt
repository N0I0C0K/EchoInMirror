package com.eimsound.daw.impl.clips.midi.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.zIndex
import com.eimsound.audioprocessor.data.DefaultEnvelopePointList
import com.eimsound.audioprocessor.data.EnvelopePointList
import com.eimsound.audioprocessor.data.MIDI_CC_RANGE
import com.eimsound.audioprocessor.data.midi.NoteMessage
import com.eimsound.daw.actions.GlobalEnvelopeEditorEventHandler
import com.eimsound.daw.actions.doAddOrRemoveMidiCCEventAction
import com.eimsound.daw.actions.doNoteVelocityAction
import com.eimsound.daw.api.EchoInMirror
import com.eimsound.daw.api.MidiClipEditor
import com.eimsound.daw.api.MutableMidiCCEvents
import com.eimsound.daw.components.*
import com.eimsound.daw.components.IconButton
import com.eimsound.daw.components.utils.EditAction
import com.eimsound.daw.components.utils.clickableWithIcon
import com.eimsound.daw.utils.BasicEditor
import com.eimsound.daw.utils.FloatRange
import com.eimsound.daw.utils.SerializableEditor
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

interface EventType : BasicEditor {
    val range: FloatRange
    val name: String
    val isInteger: Boolean
    @Composable
    fun Editor()
}

class VelocityEvent(private val editor: MidiClipEditor) : EventType {
    override val range get() = MIDI_CC_RANGE
    override val name = "力度"
    override val isInteger = true

    @Composable
    override fun Editor() {
        if (editor !is DefaultMidiClipEditor) return
        val primaryColor = MaterialTheme.colorScheme.primary
        var delta by remember { mutableStateOf(0) }
        var selectedNote by remember { mutableStateOf<NoteMessage?>(null) }
        editor.apply {
            Spacer(Modifier.fillMaxSize().drawBehind {
                if (size.height < 1) return@drawBehind
                val noteWidthPx = noteWidth.value.toPx()
                val offsetOfDelta = -delta / 127F * size.height
                val offsetX = if (action == EditAction.MOVE) deltaX * noteWidthPx else 0f
                val scrollX = horizontalScrollState.value
                val startTime = clip.time - clip.start
                val trackColor = track.color
                notesInView.fastForEach {
                    val isSelected = selectedNotes.contains(it)
                    val x = (startTime + it.time) * noteWidthPx - scrollX + (if (isSelected) offsetX else 0f)
                    val y = (size.height * (1 - it.velocity / 127F) + (if (isSelected || selectedNote == it) offsetOfDelta else 0f))
                        .coerceIn(0f, size.height - 1)
                    val color = if (isSelected) primaryColor else trackColor
                    drawLine(color, Offset(x, y), Offset(x, size.height), 2f)
                    drawCircle(color, 4F, Offset(x, y))
                }
            }.pointerInput(editor) {
                detectDragGestures({
                    val x = it.x + horizontalScrollState.value
                    val noteWidthPx = noteWidth.value.toPx()
                    val startTime = clip.time - clip.start
                    for (i in startNoteIndex until clip.clip.notes.size) {
                        val note = clip.clip.notes[i]
                        if (((startTime + note.time) * noteWidthPx - x).absoluteValue <= 2) {
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
}

class CCEvent(private val editor: MidiClipEditor, eventId: Int, points: EnvelopePointList) : EventType, SerializableEditor {
    override val range get() = MIDI_CC_RANGE
    override val name = "CC:${eventId}"
    override val isInteger = true
    private val envEditor = EnvelopeEditor(points, range, eventHandler = GlobalEnvelopeEditorEventHandler)
    @Composable
    override fun Editor() {
        if (editor !is DefaultMidiClipEditor) return
        editor.apply {
            envEditor.Editor(
                clip.start - clip.time + with (LocalDensity.current) { horizontalScrollState.value / noteWidth.value.toPx() },
                clip.track?.color ?: MaterialTheme.colorScheme.primary,
                noteWidth, editUnit = EchoInMirror.editUnit, horizontalScrollState = horizontalScrollState,
                clipStartTime = clip.time
            )
        }
    }

    override fun copy() { envEditor.copy() }
    override fun paste() { envEditor.paste() }
    override fun cut() { envEditor.cut() }
    override fun copyAsString() = envEditor.copyAsString()
    override fun pasteFromString(value: String) { envEditor.pasteFromString(value) }
    override fun delete() { envEditor.delete() }
    override fun selectAll() { envEditor.selectAll() }
}

private val defaultCCEvents = sortedMapOf(
    1 to "调制",
    7 to "音量",
    10 to "声相",
    11 to "表情",
    64 to "延音踏板",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
private fun FloatingLayerProvider.openEventSelectorDialog(events: MutableMidiCCEvents) {
    val key = Any()
    val close = { closeFloatingLayer(key) }
    openFloatingLayer(::closeFloatingLayer, key = key, hasOverlay = true) {
        Dialog(close, modifier = Modifier.widthIn(max = 460.dp)) {
            Text("选择 CC 事件", style = MaterialTheme.typography.titleMedium)
            val keys = events.keys
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                keys.sorted().forEach {
                    val name = defaultCCEvents[it]
                    InputChip(true, {
                        events.doAddOrRemoveMidiCCEventAction(it)
                    }, { Text(if (name == null) "CC $it" else "CC $it ($name)") },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "删除") },
                    )
                }
            }
            Divider(Modifier.padding(vertical = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                defaultCCEvents.forEach { (id, name) ->
                    if (id in keys) return@forEach
                    InputChip(false, {
                        events.doAddOrRemoveMidiCCEventAction(id, DefaultEnvelopePointList())
                    }, { Text("CC $id ($name)") },
                        trailingIcon = { Icon(Icons.Filled.Add, contentDescription = "添加") },
                    )
                }
                Box {
                    var cc by remember { mutableStateOf(1) }
                    TextField(cc.toString(), { cc = it.toIntOrNull()?.coerceIn(1, 127) ?: 1 }, Modifier.width(140.dp),
                        leadingIcon = { Text("CC") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        trailingIcon = {
                            IconButton({
                                events.doAddOrRemoveMidiCCEventAction(cc, DefaultEnvelopePointList())
                            }, enabled = cc !in keys) {
                                Icon(Icons.Filled.Add, contentDescription = "添加")
                            }
                        }
                    )
                }
            }
        }
    }
}

internal val eventSelectorHeight = 26.dp

@OptIn(ExperimentalTextApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun EventEditor(editor: DefaultMidiClipEditor) {
    editor.apply {
        Column(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) {
            isEventPanelActive = true
        }) {
            Surface(Modifier.fillMaxWidth().height(eventSelectorHeight).zIndex(2f), shadowElevation = 5.dp) {
                Row {
                    Text("力度", (
                            if (selectedEvent is VelocityEvent) Modifier.background(MaterialTheme.colorScheme.primary.copy(0.2F))
                            else Modifier)
                        .height(eventSelectorHeight)
                        .clickableWithIcon {
                            selectedEvent = VelocityEvent(editor)
                        }.padding(4.dp, 2.dp), style = MaterialTheme.typography.labelLarge)
                    clip.clip.events.forEach { (id, points) ->
                        Text("CC:${id}", (
                                if (selectedEvent?.name == "CC:${id}") Modifier.background(MaterialTheme.colorScheme.primary.copy(0.2F))
                                else Modifier).height(eventSelectorHeight).clickableWithIcon {
                            selectedEvent = CCEvent(editor, id, points)
                        }.padding(4.dp, 2.dp), style = MaterialTheme.typography.labelLarge)
                    }
                    val floatingLayerProvider = LocalFloatingLayerProvider.current
                    Icon(Icons.Default.Add, "添加", Modifier.size(eventSelectorHeight).clickableWithIcon {
                        floatingLayerProvider.openEventSelectorDialog(clip.clip.events)
                    })
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
                        val isInteger = selectedEvent?.isInteger ?: false
                        val last = selectedEvent?.range?.endInclusive ?: 127F
                        for (i in 1..lines) {
                            drawLine(
                                lineColor,
                                Offset(size.width - 10, lineSize * i),
                                Offset(size.width, lineSize * i),
                                1F
                            )
                            val value = (last / lines) * (lines - i)
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
                    val range = remember(clip.time, clip.duration) { clip.time..(clip.time + clip.duration) }
                    EchoInMirror.currentPosition.apply {
                        EditorGrid(noteWidth, horizontalScrollState, range, ppq, timeSigDenominator, timeSigNumerator)
                    }
                    selectedEvent?.Editor()
                }
            }
        }
    }
}
