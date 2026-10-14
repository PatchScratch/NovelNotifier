package com.patchscratch.novelnotifier

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * The in-app reading session (Phase 4b). Holds the target the reader
 * composable renders; Route.Reader / Route.XlateFiles sit above it in the
 * Router. A plain ViewModel — no context needed.
 */
class ReaderViewModel : ViewModel() {

    var target by mutableStateOf<ReaderTarget?>(null)
        private set

    fun open(target: ReaderTarget) {
        this.target = target
    }

    fun close() {
        target = null
    }
}
