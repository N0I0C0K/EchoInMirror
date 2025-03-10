package com.eimsound.daw.utils.impl

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.eimsound.daw.utils.ManualState
import com.eimsound.daw.utils.UndoManager
import com.eimsound.daw.utils.UndoableAction
import com.eimsound.daw.utils.UndoableActionExecuteException
import io.github.oshai.KotlinLogging

private val logger = KotlinLogging.logger { }
class DefaultUndoManager: UndoManager {
    private val _actions = ArrayDeque<UndoableAction>()
    private val manualState = ManualState()
    override val actions: List<UndoableAction>
        get() {
            manualState.read()
            return _actions
        }

    override val limit = 100
    override var cursor by mutableStateOf(0)
    override val errorHandlers = mutableSetOf<(UndoableActionExecuteException) -> Unit>()

    private suspend fun tryRun(block: suspend () -> Boolean) = try {
        block()
    } catch (e: Throwable) {
        val err = UndoableActionExecuteException("Failed to execute action", e)
        logger.error(err) { }
        errorHandlers.forEach { it(err) }
        false
    }

    override suspend fun undo(steps: Int): Boolean {
        if (steps <= 0) return true
        for (i in 0 until steps.coerceAtMost(cursor)) {
            if (!tryRun(actions[cursor - 1]::undo)) return false
            cursor--
        }
        manualState.update()
        return true
    }

    override suspend fun redo(steps: Int): Boolean {
        if (steps <= 0) return true
        for (i in 0 until steps.coerceAtMost(actions.size - cursor)) {
            if (!tryRun(actions[cursor]::execute)) return false
            cursor++
        }
        manualState.update()
        return true
    }

    override suspend fun execute(action: UndoableAction): Boolean {
        if (cursor < actions.size) {
            _actions.subList(cursor, actions.size).clear()
        }
        if (actions.isNotEmpty()) {
            val last = actions.last()
            val merged = last.merge(action)
            if (merged != null) {
                if (!tryRun(merged::execute)) return false
                _actions[cursor - 1] = merged
                manualState.update()
                return true
            }
        }
        if (!tryRun(action::execute)) return false
        _actions.add(action)
        if (actions.size > limit) {
            _actions.removeFirst()
        } else {
            cursor++
        }
        manualState.update()
        return true
    }

    override suspend fun reset(): Boolean {
        if (cursor == 0) return true
        for (i in 0 until cursor) {
            if (!tryRun(actions[cursor - 1]::undo)) return false
            cursor--
        }
        manualState.update()
        return true
    }

    override fun clear() {
        _actions.clear()
        cursor = 0
        manualState.update()
    }
}