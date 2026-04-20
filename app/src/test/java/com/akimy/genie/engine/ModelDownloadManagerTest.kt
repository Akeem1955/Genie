package com.akimy.genie.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadManagerTest {

    @Test
    fun `await terminal download state exits on ready`() = runBlocking {
        val states = MutableSharedFlow<DownloadState>(replay = 1)
        states.tryEmit(DownloadState.Idle)

        val deferred = async { awaitTerminalDownloadState(states) }
        states.emit(DownloadState.Checking)
        states.emit(DownloadState.Ready)

        assertEquals(DownloadState.Ready, deferred.await())
    }

    @Test
    fun `await terminal download state exits on failed`() = runBlocking {
        val states = MutableSharedFlow<DownloadState>(replay = 1)
        states.tryEmit(DownloadState.Idle)

        val deferred = async { awaitTerminalDownloadState(states) }
        val failed = DownloadState.Failed("network")
        states.emit(DownloadState.Checking)
        states.emit(failed)

        assertEquals(failed, deferred.await())
    }

    @Test
    fun `await terminal download state ignores intermediate states until terminal emission`() = runBlocking {
        val states = MutableSharedFlow<DownloadState>(replay = 1)
        states.tryEmit(DownloadState.Idle)
        val progressEvents = mutableListOf<Int>()

        val deferred = async {
            withTimeout(1_000L) {
                awaitTerminalDownloadState(states) { progress ->
                    progressEvents += progress.progressPercent
                }
            }
        }

        states.emit(DownloadState.Checking)
        states.emit(
            DownloadState.Downloading(
                progressPercent = 40,
                receivedBytes = 40,
                totalBytes = 100,
                bytesPerSecond = 10,
                remainingMs = 6_000,
            )
        )
        states.emit(DownloadState.Ready)

        assertEquals(DownloadState.Ready, deferred.await())
        assertTrue(progressEvents.contains(40))
    }
}
