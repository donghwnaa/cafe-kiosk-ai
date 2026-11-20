package com.example.cafe_kiosk_ai

import android.os.Process
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore

internal const val IDLE_MODEL_AVAILABLE_BYTES = 1024L * 1024 * 1024

/** Serializes native model initialization and work across app processes. */
internal class ModelMemoryLease private constructor(
    private val stage: String,
    private val handle: RandomAccessFile,
    private val fileLock: FileLock
) : Closeable {
    override fun close() {
        try {
            Log.i(TAG, "RELEASING stage=$stage pid=" + Process.myPid())
            fileLock.release()
        } finally {
            try {
                handle.close()
            } finally {
                local.release()
            }
        }
    }

    companion object {
        private const val TAG = "MalloModelMemory"
        // Keep one channel open per process. The file lock also covers the separate TTS service.
        private val local = Semaphore(1, true)

        fun acquire(cacheDir: File, stage: String, cancelled: () -> Boolean): ModelMemoryLease? {
            while (!cancelled()) {
                if (!local.tryAcquire(50, TimeUnit.MILLISECONDS)) continue
                var handle: RandomAccessFile? = null
                var acquired = false
                try {
                    val lockDir = File(cacheDir.parentFile, "files")
                    check(lockDir.isDirectory || lockDir.mkdirs() || lockDir.isDirectory)
                    handle = RandomAccessFile(File(lockDir, "model-memory.lock"), "rw")
                    while (!cancelled()) {
                        val lock = handle.channel.tryLock()
                        if (lock != null) {
                            Log.i(TAG, "ACQUIRED stage=$stage pid=" + Process.myPid())
                            acquired = true
                            return ModelMemoryLease(stage, handle, lock)
                        }
                        Thread.sleep(50)
                    }
                } finally {
                    if (!acquired) {
                        try { handle?.close() } finally { local.release() }
                    }
                }
            }
            return null
        }
    }
}
