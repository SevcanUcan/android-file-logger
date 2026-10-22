package com.filelogger

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class SessionLease private constructor(
    private val coordinator: LogStorageCoordinator,
    private val marker: File,
    private val randomAccessFile: RandomAccessFile,
    private val channel: FileChannel,
    private val lock: FileLock
) {
    private val closed = AtomicBoolean(false)

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        coordinator.withExclusiveAccess {
            runCatching { lock.release() }
            runCatching { channel.close() }
            runCatching { randomAccessFile.close() }
            if (!marker.delete() && marker.exists()) {
                throw IOException("Failed to remove session lease: ${marker.absolutePath}")
            }
        }
    }

    companion object {
        private const val MARKER_PREFIX = ".filelogger-active-"
        private const val MARKER_SUFFIX = ".lease"

        fun acquire(coordinator: LogStorageCoordinator, directory: File): SessionLease =
            coordinator.withExclusiveAccess {
                if (!directory.exists() && !directory.mkdirs()) {
                    throw IOException("Failed to create active session directory: ${directory.absolutePath}")
                }
                val marker = File(directory, "$MARKER_PREFIX${UUID.randomUUID()}$MARKER_SUFFIX")
                val randomAccess = RandomAccessFile(marker, "rw")
                try {
                    val channel = randomAccess.channel
                    val lock = channel.lock()
                    SessionLease(coordinator, marker, randomAccess, channel, lock)
                } catch (error: Exception) {
                    runCatching { randomAccess.close() }
                    marker.delete()
                    throw error
                }
            }

        fun hasLiveLease(directory: File): Boolean {
            val markers = directory.listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.startsWith(MARKER_PREFIX) && it.name.endsWith(MARKER_SUFFIX) }
            var liveLeaseFound = false
            markers.forEach { marker ->
                if (liveLeaseFound) return@forEach
                val stale = try {
                    RandomAccessFile(marker, "rw").use { randomAccess ->
                        randomAccess.channel.use { channel ->
                            val probe = channel.tryLock()
                            if (probe == null) {
                                false
                            } else {
                                probe.release()
                                true
                            }
                        }
                    }
                } catch (_: OverlappingFileLockException) {
                    false
                } catch (_: Exception) {
                    false
                }
                if (stale) {
                    if (!marker.delete() && marker.exists()) liveLeaseFound = true
                } else {
                    liveLeaseFound = true
                }
            }
            return liveLeaseFound
        }
    }
}
