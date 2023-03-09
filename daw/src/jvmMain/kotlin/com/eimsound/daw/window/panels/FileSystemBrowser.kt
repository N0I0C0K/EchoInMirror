package com.eimsound.daw.window.panels

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import cafe.adriel.bonsai.core.node.Node
import com.eimsound.audioprocessor.AudioProcessorManager
import com.eimsound.audioprocessor.data.midi.*
import com.eimsound.daw.api.window.Panel
import com.eimsound.daw.api.window.PanelDirection
import com.eimsound.daw.components.*
import com.eimsound.daw.components.dragdrop.FileDraggable
import com.eimsound.daw.impl.processor.eimAudioProcessorFactory
import com.eimsound.daw.processor.PreviewerAudioProcessor
import kotlinx.coroutines.*
import okio.FileSystem
import okio.Path
import org.apache.commons.lang3.SystemUtils
import java.io.File
import javax.sound.midi.MidiSystem
import com.eimsound.daw.components.IconButton as EIMIconButton

val FileMapper = @Composable { node: Node<Path>, content: @Composable () -> Unit ->
    if (FileSystem.SYSTEM.metadata(node.content).isDirectory) content()
    else FileDraggable(node.content.toFile()) { content() }
}

val fileBrowserPreviewer = PreviewerAudioProcessor(AudioProcessorManager.instance.eimAudioProcessorFactory)

@Composable
fun CommonNode(icon: ImageVector, text: String) {
    Icon(
        icon,
        text,
        modifier = Modifier.size(20.dp, 20.dp)
    )
    Text(text, modifier = Modifier.padding(start = 4.dp))
}

@Composable
fun MidiNode(file: File, indent: Int) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = (10 * (indent + 1)).dp, top = 4.dp, bottom = 4.dp)
    ) {
        Icon(
            FileExtensionIcons["mid"]!!,
            contentDescription = null,
            modifier = Modifier.size(20.dp, 20.dp),
        )
        Text(file.name, modifier = Modifier.padding(start = 4.dp))
        EIMIconButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.padding(end = 4.dp).size(20.dp, 20.dp)
        ) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null
            )
        }

    }
    if (expanded) {
        val midiTracks = MidiSystem.getSequence(file).toMidiTracks()
        midiTracks.forEachIndexed { index, midiTrack ->

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = (10 * (indent + 2)).dp, top = 4.dp, bottom = 4.dp)
            ){
                CommonNode(FileExtensionIcons["midiTrack"]!!, midiTrack.name ?: "轨道 $index")
            }
        }
    }
}


@Composable
fun FileNode(file: File) {
    Icon(
        FileExtensionIcons.getOrDefault(file.extension, Icons.Outlined.DevicesOther),
        file.name,
        modifier = Modifier.size(20.dp, 20.dp)
    )
    Text(file.name, modifier = Modifier.padding(start = 4.dp))
}


@Composable
fun FileTree(file: File, indent: Int = 0) {
    var expanded by remember { mutableStateOf(false) }

    when (file.extension) {
        "mid" -> MidiNode(file, indent)
        else -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = (10 * (indent + 1)).dp, top = 4.dp, bottom = 4.dp)
            ) {
                if (file.isDirectory) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp, 20.dp),
                    )
                    Text(file.name, modifier = Modifier.padding(start = 4.dp))
                    if (file.listFiles()!!.isNotEmpty()) {
                        EIMIconButton(
                            onClick = { expanded = !expanded },
                            modifier = Modifier.padding(end = 4.dp).size(20.dp, 20.dp)
                        ) {
                            Icon(
                                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null
                            )
                        }
                    }
                } else FileNode(file)
            }
        }
    }

    if (file.isDirectory && expanded) {
        for (child in file.listFiles()!!.sorted().sortedBy { it.isFile }) {
            FileTree(child, indent + 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileTreeUI(filePath: File) {
    Column(
        modifier = Modifier
//            .widthIn(400.dp, 1200.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
    ) {
        FileTree(filePath)
    }
}

object FileSystemBrowser : Panel {
    override val name = "文件浏览"
    override val direction = PanelDirection.Vertical
    private val filePath = when {
        SystemUtils.IS_OS_WINDOWS -> """C:\"""
        SystemUtils.IS_OS_LINUX -> SystemUtils.getUserDir().toString()
        SystemUtils.IS_OS_MAC -> SystemUtils.getUserDir().toString()
        else -> """C:\"""
    }

    @Composable
    override fun Icon() {
        Icon(Icons.Filled.FolderOpen, name)
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalComposeUiApi::class)
    @Composable
    override fun Content() {
        Column {
            FileTreeUI(File(filePath))
        }
    }

    @Composable
    private fun PlayHead(interactionSource: MutableInteractionSource, width: IntArray) {
        val isHovered by interactionSource.collectIsHoveredAsState()
        val left: Float
        val leftDp = LocalDensity.current.run {
            left = (fileBrowserPreviewer.playPosition * width[0]).toFloat() - (if (isHovered) 2 else 1) * density
            left.toDp()
        }
        val color = MaterialTheme.colorScheme.primary
        Spacer(
            Modifier.width(if (isHovered) 4.dp else 2.dp).fillMaxHeight()
                .graphicsLayer(translationX = left)
                .hoverable(interactionSource)
                .background(color)
        )
        Spacer(Modifier.width(leftDp).fillMaxHeight().background(color.copy(0.14F)))
    }
}
