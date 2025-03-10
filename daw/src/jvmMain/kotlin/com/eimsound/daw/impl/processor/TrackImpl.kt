package com.eimsound.daw.impl.processor

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.util.fastForEach
import com.eimsound.audioprocessor.*
import com.eimsound.audioprocessor.data.PAN_RANGE
import com.eimsound.audioprocessor.data.VOLUME_RANGE
import com.eimsound.audioprocessor.data.midi.MidiEvent
import com.eimsound.audioprocessor.data.midi.MidiNoteRecorder
import com.eimsound.audioprocessor.data.midi.noteOff
import com.eimsound.audioprocessor.dsp.calcPanLeftChannel
import com.eimsound.audioprocessor.dsp.calcPanRightChannel
import com.eimsound.daw.Configuration
import com.eimsound.daw.api.ClipManager
import com.eimsound.daw.api.DefaultTrackClipList
import com.eimsound.daw.api.EchoInMirror
import com.eimsound.daw.api.ProjectInformation
import com.eimsound.daw.api.processor.*
import com.eimsound.daw.components.utils.randomColor
import com.eimsound.daw.utils.*
import com.eimsound.daw.window.panels.fileBrowserPreviewer
import io.github.oshai.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.io.path.absolutePathString
import kotlin.io.path.pathString
import kotlin.system.measureTimeMillis

private val trackLogger = KotlinLogging.logger("TrackImpl")
open class TrackImpl(description: AudioProcessorDescription, factory: TrackFactory<*>) :
    Track, AbstractAudioProcessor(description, factory) {
    override var name by mutableStateOf("NewTrack")
    override var color by mutableStateOf(randomColor(true))
    override var height by mutableStateOf(0)

    override val levelMeter = LevelMeterImpl()

    override val internalProcessorsChain = ArrayList<AudioProcessor>()
    override val preProcessorsChain: MutableList<TrackAudioProcessorWrapper> = mutableStateListOf()
    override val postProcessorsChain: MutableList<TrackAudioProcessorWrapper> = mutableStateListOf()
    override val subTracks: MutableList<Track> = mutableStateListOf()

    private val pendingMidiBuffer = Collections.synchronizedList(ArrayList<Int>())
    private val pendingNoteOns = LongArray(128)
    private val noteRecorder = MidiNoteRecorder()
    private var lastUpdateTime = 0L

    private var tempBuffer = arrayOf(FloatArray(1024), FloatArray(1024))
    private var tempBuffer2 = arrayOf(FloatArray(1024), FloatArray(1024))
    override var isRendering by mutableStateOf(false)
    override var isBypass by observableMutableStateOf(false, ::stateChange)
    override var isSolo by observableMutableStateOf(false, ::stateChange)
    override var isDisabled by observableMutableStateOf(false, ::stateChange)

    private val panParameter = audioProcessorParameterOf("pan", "声相", PAN_RANGE, 0F)
    private val volumeParameter = audioProcessorParameterOf("volume", "电平", VOLUME_RANGE, 1F)
    override var pan by panParameter
    override var volume by volumeParameter
    override val parameters = listOf(panParameter, volumeParameter)

    @Suppress("LeakingThis")
    override val clips = DefaultTrackClipList(this)
    private var lastClipIndex = -1

    override suspend fun processBlock(
        buffers: Array<FloatArray>,
        position: CurrentPosition,
        midiBuffer: ArrayList<Int>
    ) {
        if (isBypass || isDisabled) return
        if (pendingMidiBuffer.isNotEmpty()) {
            midiBuffer.addAll(pendingMidiBuffer)
            pendingMidiBuffer.clear()
        }
        if (position.isPlaying) {
            noteRecorder.forEachNotes {
                pendingNoteOns[it] -= position.bufferSize.toLong()
                if (pendingNoteOns[it] <= 0) {
                    noteRecorder.unmarkNote(it)
                    midiBuffer.add(noteOff(0, it).rawData)
                    midiBuffer.add(pendingNoteOns[it].toInt().coerceAtLeast(0))
                }
            }
            val startTime = position.timeInPPQ
            val blockEndSample = position.timeInSamples + position.bufferSize
            if (lastClipIndex == -1) lastClipIndex = clips.binarySearch { it.time < startTime }
            for (i in lastClipIndex..clips.lastIndex) {
                val clip = clips[i]
                val startTimeInSamples = position.convertPPQToSamples(clip.time)
                val endTimeInSamples = position.convertPPQToSamples(clip.time + clip.duration)
                if (endTimeInSamples < position.timeInSamples) {
                    lastClipIndex = i + 1
                    continue
                }
                if (startTimeInSamples > blockEndSample) break
                @Suppress("TYPE_MISMATCH")
                clip.clip.factory.processBlock(clip, buffers, position, midiBuffer, noteRecorder, pendingNoteOns)
            }
        }
        preProcessorsChain.forEach { if (!it.isBypassed) it.processor.processBlock(buffers, position, midiBuffer) }
        if (subTracks.isNotEmpty()) {
            tempBuffer[0].fill(0F)
            tempBuffer[1].fill(0F)
            runBlocking {
                val bus = EchoInMirror.bus
                subTracks.forEach {
                    if (it.isBypass || it.isDisabled || it.isRendering) return@forEach
                    launch {
                        val buffer = if (it is TrackImpl) it.tempBuffer2.apply {
                            this[0].fill(0F)
                            this[1].fill(0F)
                        } else arrayOf(FloatArray(buffers[0].size), FloatArray(buffers[1].size))
                        buffers[0].copyInto(buffer[0])
                        buffers[1].copyInto(buffer[1])
                        it.processBlock(buffer, position, ArrayList(midiBuffer))
                        if (bus != null) tempBuffer.mixWith(buffer)
                    }
                }
            }
            tempBuffer[0].copyInto(buffers[0])
            tempBuffer[1].copyInto(buffers[1])
        }

        if (position.isRealtime) internalProcessorsChain.fastForEach { it.processBlock(buffers, position, midiBuffer) }
        postProcessorsChain.forEach { if (!it.isBypassed) it.processor.processBlock(buffers, position, midiBuffer) }

        var leftPeak = 0F
        var rightPeak = 0F
        val leftFactor = calcPanLeftChannel() * volume
        val rightFactor = calcPanRightChannel() * volume
        for (i in buffers[0].indices) {
            buffers[0][i] *= leftFactor
            val tmp = buffers[0][i]
            if (tmp > leftPeak) leftPeak = tmp
        }
        for (i in buffers[1].indices) {
            buffers[1][i] *= rightFactor
            val tmp = buffers[1][i]
            if (tmp > rightPeak) rightPeak = tmp
        }
        levelMeter.left = levelMeter.left.update(leftPeak)
        levelMeter.right = levelMeter.right.update(rightPeak)
        lastUpdateTime += (1000.0 * position.bufferSize / position.sampleRate).toLong()
        if (lastUpdateTime > 300) {
            levelMeter.cachedMaxLevelString = levelMeter.maxLevel.toString()
            lastUpdateTime = 0
        }
    }

    override fun prepareToPlay(sampleRate: Int, bufferSize: Int) {
        tempBuffer = arrayOf(FloatArray(bufferSize), FloatArray(bufferSize))
        tempBuffer2 = arrayOf(FloatArray(bufferSize), FloatArray(bufferSize))
        preProcessorsChain.forEach { it.processor.prepareToPlay(sampleRate, bufferSize) }
        subTracks.forEach { it.prepareToPlay(sampleRate, bufferSize) }
        postProcessorsChain.forEach { it.processor.prepareToPlay(sampleRate, bufferSize) }
        internalProcessorsChain.fastForEach { it.prepareToPlay(sampleRate, bufferSize) }
    }

    override fun close() {
        if (EchoInMirror.selectedTrack == this) EchoInMirror.selectedTrack = null
        preProcessorsChain.forEach { it.processor.close() }
        subTracks.forEach(AutoCloseable::close)
        postProcessorsChain.forEach { it.processor.close() }
        internalProcessorsChain.fastForEach(AutoCloseable::close)
        clips.fastForEach { (it.clip as? AutoCloseable)?.close() }
    }

    override fun recover(path: String) {
        runBlocking {
            val dir = Paths.get(path)
            val processorsDir = dir.resolve("processors").absolutePathString()
            preProcessorsChain.forEach { launch { it.processor.recover(processorsDir) } }
            if (subTracks.isNotEmpty()) {
                val tracksDir = dir.resolve("tracks")
                subTracks.forEach { launch { it.recover(tracksDir.resolve(it.id).absolutePathString()) } }
            }
            postProcessorsChain.forEach { launch { it.processor.recover(processorsDir) } }
            if (clips.isNotEmpty()) {
                val clipsDir = dir.resolve("clips").absolutePathString()
                clips.fastForEach {
                    val clip = it.clip
                    if (clip is Recoverable) launch { clip.recover(clipsDir) }
                }
            }
            internalProcessorsChain.fastForEach { launch { it.recover(processorsDir) } }
        }
    }

    override fun playMidiEvent(midiEvent: MidiEvent, time: Int) {
        pendingMidiBuffer.add(midiEvent.rawData)
        pendingMidiBuffer.add(time)
    }

    override fun onSuddenChange() {
        stopAllNotes()
        lastClipIndex = -1
        pendingNoteOns.fill(0L)
        noteRecorder.reset()
        clips.fastForEach { it.reset() }
        preProcessorsChain.forEach { it.processor.onSuddenChange() }
        subTracks.forEach(Track::onSuddenChange)
        postProcessorsChain.forEach { it.processor.onSuddenChange() }
        internalProcessorsChain.fastForEach(AudioProcessor::onSuddenChange)
    }

//    override fun stateChange() {
//        levelMeter.reset()
//        subTracks.fastForEach(Track::stateChange)
//    }

    @Suppress("UNUSED_PARAMETER")
    private fun stateChange(unused: Boolean) { levelMeter.reset() }

    override fun onRenderStart() {
        isRendering = true
    }

    override fun onRenderEnd() {
        isRendering = false
    }

    protected fun JsonObjectBuilder.buildJson() {
        put("factory", factory.name)
        put("id", id)
        putNotDefault("name", name)
        put("color", color.value.toLong())
        putNotDefault("height", height)
        putNotDefault("isBypass", isBypass)
        putNotDefault("isSolo", isSolo)
        putNotDefault("isDisabled", isDisabled)
        putNotDefault("subTracks", subTracks.map { it.id })
        putNotDefault("preProcessorsChain", preProcessorsChain)
        putNotDefault("postProcessorsChain", postProcessorsChain)
        putNotDefault("clips", clips)
    }
    override fun toJson() = buildJsonObject { buildJson() }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun save(path: String) {
        withContext(Dispatchers.IO) {
            val dir = Paths.get(path)
            if (!Files.exists(dir)) Files.createDirectory(dir)
            val trackFile = dir.resolve("track.json").toFile()
            JsonPrettier.encodeToStream(toJson(), trackFile.outputStream())

            if (clips.isNotEmpty()) {
                val clipsDir = dir.resolve("clips")
                if (!Files.exists(clipsDir)) Files.createDirectory(clipsDir)
                clips.fastForEach {
                    launch {
                        @Suppress("TYPE_MISMATCH")
                        it.clip.factory.save(it.clip, clipsDir.resolve(it.clip.id).absolutePathString())
                    }
                }
            }

            if (subTracks.isNotEmpty()) {
                val tracksDir = dir.resolve("tracks")
                if (!Files.exists(tracksDir)) Files.createDirectory(tracksDir)
                subTracks.forEach { launch { it.save(tracksDir.resolve(it.id).absolutePathString()) } }
            }

            if (preProcessorsChain.isNotEmpty() || postProcessorsChain.isNotEmpty()) {
                val processorsDir = dir.resolve("processors")
                if (!Files.exists(processorsDir)) Files.createDirectory(processorsDir)
                preProcessorsChain.forEach {
                    launch { it.processor.save(processorsDir.resolve(it.processor.id).absolutePathString()) }
                }
                postProcessorsChain.forEach {
                    launch { it.processor.save(processorsDir.resolve(it.processor.id).absolutePathString()) }
                }
            }
        }
    }

    override fun fromJson(json: JsonElement) {
        json as JsonObject
        id = json["id"]!!.asString()
        json["name"]?.asString()?.let { name = it }
        json["color"]?.asLong()?.let { color = Color(it.toULong()) }
        json["height"]?.asInt()?.let { height = it }
        json["isBypass"]?.asBoolean()?.let { isBypass = it }
        json["isSolo"]?.asBoolean()?.let { isSolo = it }
        json["isDisabled"]?.asBoolean()?.let { isDisabled = it }
    }

    override suspend fun load(path: String, json: JsonObject) {
        withContext(Dispatchers.Default) {
            try {
                fromJson(json)
                val dir = Paths.get(path)

                json["clips"]?.let { json ->
                    val clipsDir = dir.resolve("clips").absolutePathString()
                    clips.addAll(json.jsonArray.map {
                        async {
                            tryOrNull(trackLogger, "Failed to load clip: $it") {
                                ClipManager.instance.createTrackClip(clipsDir, it as JsonObject)
                            }
                        }
                    }.awaitAll().filterNotNull())
                }

                json["subTracks"]?.let { json ->
                    val tracksDir = dir.resolve("tracks")
                    subTracks.addAll(json.jsonArray.map {
                        async {
                            tryOrNull(trackLogger, "Failed to load track: $it") {
                                val trackID = it.asString()
                                TrackManager.instance.createTrack(tracksDir.resolve(trackID).absolutePathString(), trackID)
                            }
                        }
                    }.awaitAll().filterNotNull())
                }
                val processorsDir = dir.resolve("processors").absolutePathString()
                loadAudioProcessors(preProcessorsChain, json["preProcessorsChain"], processorsDir)
                loadAudioProcessors(postProcessorsChain, json["postProcessorsChain"], processorsDir)
            } catch (_: FileNotFoundException) { }
        }
    }

    private suspend fun loadAudioProcessors(list: MutableList<TrackAudioProcessorWrapper>, json: JsonElement?, processorsDir: String) {
        if (json == null) return
        withContext(Dispatchers.Default) {
            list.addAll(json.jsonArray.map {
                async {
                    tryOrNull(trackLogger, "Failed to load audio processor: $it") {
                        val processor = (it as JsonObject)["processor"]!!
                        DefaultTrackAudioProcessorWrapper(
                            if (processor is JsonObject) AudioProcessorManager.instance.createAudioProcessor(processorsDir, processor)
                            else AudioProcessorManager.instance.createAudioProcessor(processorsDir, processor.asString())
                        ).apply { fromJson(it) }
                    }
                }
            }.awaitAll().filterNotNull())
        }
    }

    override fun toString(): String {
        return "TrackImpl(name='$name', preProcessorsChain=${preProcessorsChain.size}, " +
        "postProcessorsChain=${postProcessorsChain.size}, subTracks=${subTracks.size}, clips=${clips.size})"
    }
}

private val backupFileTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

private val busLogger = KotlinLogging.logger("BusImpl")
class BusImpl(
    override val project: ProjectInformation,
    description: AudioProcessorDescription, factory: TrackFactory<Track>
) : TrackImpl(description, factory), Bus {
    override var name = "Bus"
    override var lastSaveTime by mutableStateOf(System.currentTimeMillis())

    override var channelType by mutableStateOf(ChannelType.STEREO)
    override var color
        get() = Color.Transparent
        set(_) { }

    @OptIn(DelicateCoroutinesApi::class)
    private val autoSaveJob = GlobalScope.launch {
        while (true) {
            delay(10 * 60 * 1000L) // 10 minutes
            save()
        }
    }

    init {
        internalProcessorsChain.add(fileBrowserPreviewer)
    }

    override fun toJson() = buildJsonObject {
        buildJson()
        putNotDefault("channelType", channelType.ordinal)
    }

    override fun fromJson(json: JsonElement) {
        json as JsonObject
        super.fromJson(json)
        json["channelType"]?.asInt()?.let { channelType = ChannelType.values()[it] }
    }

    private suspend fun backup() {
        withContext(Dispatchers.IO) {
            val backupDir = project.root.resolve("backup")
            if (!Files.exists(backupDir)) Files.createDirectory(backupDir)
            var backupRoot: Path
            var i = 0
            do {
                val time = LocalDateTime.now().format(backupFileTimeFormatter)
                backupRoot = backupDir.resolve(if (i == 0) time else "$time-$i")
                i++
            } while (Files.exists(backupRoot))
            Files.createDirectory(backupRoot)
            for (it in Files.walk(project.root)) {
                val relativePath = project.root.relativize(it)
                if (it == project.root || relativePath.startsWith("backup")) continue
                val target = backupRoot.resolve(relativePath)
                launch {
                    withContext(Dispatchers.IO) {
                        if (Files.isDirectory(it)) Files.createDirectory(target)
                        else Files.copy(it, target)
                    }
                }
            }
        }
    }

    override suspend fun save() {
        busLogger.info("Saving project: $project to ${project.root.pathString}")
        val cost = measureTimeMillis {
            val time = System.currentTimeMillis()
            project.timeCost += (time - lastSaveTime).toInt()
            lastSaveTime = time
            project.save()
            super.save(project.root.pathString)
            backup()
        }
        busLogger.info("Saved project by cost ${cost}ms")
    }

    override suspend fun save(path: String) {
        busLogger.info("Saving project: $project to $path")
        val cost = measureTimeMillis {
            val time = System.currentTimeMillis()
            project.timeCost += (time - lastSaveTime).toInt()
            lastSaveTime = time
            project.save(Paths.get(path))
            super.save(path)
        }
        busLogger.info("Saved project by cost ${cost}ms")
    }

    override suspend fun processBlock(
        buffers: Array<FloatArray>,
        position: CurrentPosition,
        midiBuffer: ArrayList<Int>
    ) {
        super.processBlock(buffers, position, midiBuffer)

        when (channelType) {
            ChannelType.LEFT -> buffers[0].copyInto(buffers[1])
            ChannelType.RIGHT -> buffers[1].copyInto(buffers[0])
            ChannelType.MONO -> {
                for (i in 0 until position.bufferSize) {
                    buffers[0][i] = (buffers[0][i] + buffers[1][i]) / 2
                    buffers[1][i] = buffers[0][i]
                }
            }
            ChannelType.SIDE -> {
                for (i in 0 until position.bufferSize) {
                    val mid = (buffers[0][i] + buffers[1][i]) / 2
                    buffers[0][i] -= mid
                    buffers[1][i] -= mid
                }
            }
            else -> {}
        }

        if (position.isRealtime && Configuration.autoCutOver0db) {
            repeat(buffers.size) { ch ->
                repeat(buffers[ch].size) {
                    if (buffers[ch][it] > 1f) buffers[ch][it] = 1f
                    else if (buffers[ch][it] < -1f) buffers[ch][it] = -1f
                }
            }
        }
    }

    override fun close() {
        autoSaveJob.cancel()
        super.close()
    }

    override fun recover(path: String) = throw UnsupportedOperationException("Bus cannot be recovered")
}
