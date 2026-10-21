package com.filelogger.remote

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

internal interface RemoteSpoolStore {
    fun persist(payload: ByteArray, suffix: String, batchId: String): Boolean
}

internal class FileRemoteSpoolStore(
    private val directory: File,
    private val maxPendingBatches: Int = Int.MAX_VALUE,
    private val payloadWriter: (File, ByteArray) -> Unit = ::writeAndSync
) : RemoteSpoolStore {
    private val canonicalDirectory = directory.canonicalFile

    override fun persist(payload: ByteArray, suffix: String, batchId: String): Boolean {
        if (payload.isEmpty() || payload.size.toLong() > RemoteSpoolFiles.MAX_BATCH_PAYLOAD_BYTES) return false
        var temp: File? = null
        return try {
            RemoteSpoolSynchronization.withLock(canonicalDirectory) {
                if (RemoteSpoolFiles.pauseMarker(canonicalDirectory).exists()) {
                    return@withLock false
                }
                if (maxPendingBatches != Int.MAX_VALUE) {
                    val pendingBatches = canonicalDirectory.listFiles()
                        .orEmpty()
                        .count { file ->
                            RemoteSpoolFiles.isCommittedBatch(file) ||
                                file.name.startsWith(RemoteSpoolClaimCoordinator.CLAIM_PREFIX)
                        }
                    if (pendingBatches >= maxPendingBatches) {
                        return@withLock false
                    }
                }
                temp = File.createTempFile("batch_${batchId}_", ".tmp", canonicalDirectory)
                payloadWriter(temp!!, payload)
                val target = File(canonicalDirectory, "batch_$batchId$suffix")
                if (target.exists()) {
                    throw IOException("Remote spool target already exists: ${target.absolutePath}")
                }
                if (!temp!!.renameTo(target)) {
                    throw IOException("Failed to commit remote spool batch")
                }
                temp = null
                true
            }
        } catch (_: OverlappingFileLockException) {
            false
        } catch (_: Exception) {
            false
        } finally {
            temp?.delete()
        }
    }

    private companion object {
        fun writeAndSync(file: File, payload: ByteArray) {
            FileOutputStream(file).use { stream ->
                stream.write(payload)
                stream.fd.sync()
            }
        }
    }
}

internal class RemoteSpoolClaimCoordinator(directory: File) {
    private val canonicalDirectory = directory.canonicalFile

    fun claimNext(excludedOriginalNames: Set<String> = emptySet()): RemoteSpoolClaim? =
        RemoteSpoolSynchronization.withLock(canonicalDirectory) {
        if (RemoteSpoolFiles.pauseMarker(canonicalDirectory).exists()) return@withLock null
        recoverStaleClaimsLocked()
        val source = canonicalDirectory.listFiles()
            .orEmpty()
            .filter(::isCommittedBatch)
            .filterNot { it.name in excludedOriginalNames }
            .sortedBy(File::getName)
            .firstOrNull()
            ?: return@withLock null
        val claimed = File(canonicalDirectory, "$CLAIM_PREFIX${source.name}")
        if (claimed.exists() || !source.renameTo(claimed)) return@withLock null
        val randomAccess = RandomAccessFile(claimed, "rw")
        try {
            val channel = randomAccess.channel
            val lock = channel.lock()
            RemoteSpoolClaim(
                file = claimed,
                originalName = source.name,
                randomAccessFile = randomAccess,
                channel = channel,
                lock = lock,
                directory = canonicalDirectory
            )
        } catch (error: Exception) {
            runCatching { randomAccess.close() }
            claimed.renameTo(source)
            throw error
        }
        }

    fun setDeliveryEnabled(enabled: Boolean): Boolean = runCatching {
        RemoteSpoolSynchronization.withLock(canonicalDirectory) {
            val marker = RemoteSpoolFiles.pauseMarker(canonicalDirectory)
            if (enabled) {
                if (marker.exists() && !marker.delete()) {
                    throw IOException("Failed to remove remote delivery pause marker")
                }
            } else if (!marker.exists()) {
                RemoteSpoolFiles.writeAndSync(marker, "paused-v1\n".toByteArray())
            }
        }
        true
    }.getOrDefault(false)

    fun isDeliveryEnabled(): Boolean = !RemoteSpoolFiles.pauseMarker(canonicalDirectory).exists()

    fun erasePendingData(deadlineNanos: Long): Boolean {
        while (true) {
            val erased = runCatching {
                RemoteSpoolSynchronization.withLock(canonicalDirectory) {
                    recoverStaleClaimsLocked()
                    if (activeClaimFilesLocked().isNotEmpty()) return@withLock false
                    relevantErasableFilesLocked().forEach { file ->
                        if (file.exists() && !file.delete()) {
                            throw IOException("Failed to erase remote spool file: ${file.name}")
                        }
                    }
                    activeClaimFilesLocked().isEmpty() && relevantErasableFilesLocked().isEmpty()
                }
            }.getOrDefault(false)
            if (erased) return true
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) return false
            Thread.sleep(TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceIn(1, 10))
        }
    }

    fun pendingFiles(): List<File> = canonicalDirectory.listFiles()
        .orEmpty()
        .filter { RemoteSpoolFiles.isCommittedBatch(it) || it.name.startsWith(CLAIM_PREFIX) }
        .sortedBy(File::getName)

    private fun recoverStaleClaimsLocked() {
        activeClaimFilesLocked().forEach { claimed ->
                val stale = try {
                    RandomAccessFile(claimed, "rw").use { randomAccess ->
                        randomAccess.channel.use { channel ->
                            val probe = channel.tryLock()
                            if (probe == null) false else {
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
                    val originalName = claimed.name.removePrefix(CLAIM_PREFIX)
                    if (!RemoteSpoolFiles.isValidCommittedName(originalName)) return@forEach
                    val original = File(canonicalDirectory, originalName)
                    if (!original.exists()) claimed.renameTo(original)
                }
            }
    }

    private fun isCommittedBatch(file: File): Boolean =
        RemoteSpoolFiles.isCommittedBatch(file)

    private fun activeClaimFilesLocked(): List<File> = canonicalDirectory.listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.startsWith(CLAIM_PREFIX) }

    private fun relevantErasableFilesLocked(): List<File> = canonicalDirectory.listFiles()
        .orEmpty()
        .filter { file ->
            file.isFile && (RemoteSpoolFiles.isCommittedBatch(file) || RemoteSpoolFiles.isBatchTemp(file))
        }

    internal companion object {
        const val CLAIM_PREFIX = ".filelogger-uploading-"
    }
}

internal class RemoteSpoolClaim(
    val file: File,
    internal val originalName: String,
    private val randomAccessFile: RandomAccessFile,
    private val channel: java.nio.channels.FileChannel,
    private val lock: java.nio.channels.FileLock,
    private val directory: File
) {
    fun readPayload(): ByteArray {
        randomAccessFile.seek(0)
        val length = randomAccessFile.length()
        if (length <= 0L) {
            throw IOException("Remote spool batch is empty")
        }
        if (length > RemoteSpoolFiles.MAX_BATCH_PAYLOAD_BYTES) {
            throw IOException("Remote spool batch is too large to read: $length bytes")
        }
        return ByteArray(length.toInt()).also(randomAccessFile::readFully)
    }

    fun complete(uploaded: Boolean): Boolean = RemoteSpoolSynchronization.withLock(directory) {
        runCatching { lock.release() }
        runCatching { channel.close() }
        runCatching { randomAccessFile.close() }
        if (uploaded) {
            file.delete() || !file.exists()
        } else {
            val original = File(directory, originalName)
            !original.exists() && file.renameTo(original)
        }
    }
}

internal class RemoteBatchIdentityStore(
    directory: File,
    private val namespaceGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    private val canonicalDirectory = directory.canonicalFile

    fun batchIdFor(originalCommittedName: String): String =
        RemoteSpoolSynchronization.withLock(canonicalDirectory) {
            require(RemoteSpoolFiles.isValidCommittedName(originalCommittedName)) {
                "Invalid committed remote batch name"
            }
            val embeddedId = RemoteSpoolFiles.embeddedBatchId(originalCommittedName)
            if (embeddedId.startsWith("v2-") && V2_ID.matches(embeddedId)) {
                return@withLock embeddedId
            }
            val namespace = loadOrCreateNamespaceLocked()
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$namespace\u0000$originalCommittedName".toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
            "legacy-v1-$digest"
        }

    private fun loadOrCreateNamespaceLocked(): String {
        val namespaceFile = File(canonicalDirectory, NAMESPACE_FILE_NAME)
        val initializedMarker = File(canonicalDirectory, NAMESPACE_INITIALIZED_MARKER)
        if (namespaceFile.exists()) {
            val value = namespaceFile.readText().trim()
            if (!NAMESPACE_VALUE.matches(value)) {
                throw IOException("Remote spool namespace metadata is corrupt")
            }
            if (!initializedMarker.exists()) {
                RemoteSpoolFiles.writeAndSync(initializedMarker, "initialized-v1\n".toByteArray())
            }
            return value.removePrefix("v1:")
        }
        if (initializedMarker.exists()) {
            throw IOException("Remote spool namespace is missing; legacy identity continuity is unknown")
        }

        val namespace = namespaceGenerator()
        require(UUID_VALUE.matches(namespace)) { "Namespace generator must return a UUID" }
        val temp = File.createTempFile(".filelogger-spool-namespace-", ".tmp", canonicalDirectory)
        try {
            RemoteSpoolFiles.writeAndSync(temp, "v1:$namespace\n".toByteArray())
            if (!temp.renameTo(namespaceFile)) {
                throw IOException("Failed to commit remote spool namespace")
            }
            RemoteSpoolFiles.writeAndSync(initializedMarker, "initialized-v1\n".toByteArray())
        } finally {
            temp.delete()
        }
        return namespace
    }

    private companion object {
        const val NAMESPACE_FILE_NAME = ".filelogger-spool-namespace"
        const val NAMESPACE_INITIALIZED_MARKER = ".filelogger-spool-namespace-initialized"
        val UUID_VALUE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
        val NAMESPACE_VALUE = Regex("v1:${UUID_VALUE.pattern}")
        val V2_ID = Regex("v2-${UUID_VALUE.pattern}")
    }
}

internal object RemoteSpoolFiles {
    const val MAX_BATCH_PAYLOAD_BYTES = 16L * 1024 * 1024
    private const val PAUSE_MARKER_NAME = ".filelogger-remote-paused"
    private val COMMITTED_NAME = Regex("batch_[A-Za-z0-9._-]+\\.jsonl(?:\\.gz)?")

    fun pauseMarker(directory: File): File = File(directory, PAUSE_MARKER_NAME)

    fun isCommittedBatch(file: File): Boolean = file.isFile && isValidCommittedName(file.name)

    fun isValidCommittedName(name: String): Boolean =
        name.length <= 255 && COMMITTED_NAME.matches(name)

    fun isBatchTemp(file: File): Boolean =
        file.isFile && file.name.startsWith("batch_") && file.name.endsWith(".tmp")

    fun embeddedBatchId(name: String): String = name
        .removePrefix("batch_")
        .removeSuffix(".gz")
        .removeSuffix(".jsonl")

    fun writeAndSync(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
    }
}

internal object RemoteSpoolSynchronization {
    private const val LOCK_FILE_NAME = ".filelogger-remote.lock"
    private val processLocks = ConcurrentHashMap<String, ReentrantLock>()

    fun <T> withLock(directory: File, block: () -> T): T {
        val canonicalDirectory = directory.canonicalFile
        val processLock = processLocks.computeIfAbsent(canonicalDirectory.path) { ReentrantLock() }
        processLock.lock()
        try {
            if (!canonicalDirectory.exists() && !canonicalDirectory.mkdirs()) {
                throw IOException("Failed to create remote spool directory: ${canonicalDirectory.absolutePath}")
            }
            if (!canonicalDirectory.isDirectory) {
                throw IOException("Remote spool path is not a directory: ${canonicalDirectory.absolutePath}")
            }
            val lockFile = File(canonicalDirectory, LOCK_FILE_NAME)
            return RandomAccessFile(lockFile, "rw").use { randomAccess ->
                randomAccess.channel.use { channel ->
                    channel.lock().use { block() }
                }
            }
        } finally {
            processLock.unlock()
        }
    }
}
