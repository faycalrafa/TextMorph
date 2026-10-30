package com.textmorph.app.llm

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.textmorph.app.data.model.ModelFileType
import com.textmorph.app.util.AppLogger
import java.io.File

/**
 * File descriptor and path resolution helper for llama.cpp GGUF models.
 */
object LlamaCppFileResolver {
    private const val TAG = "LlamaCppFileResolver"

    fun resolveModelFilePath(context: Context, modelPath: String?, modelUri: Uri?, effectiveName: String): String? {
        if (modelPath != null && !modelPath.startsWith("/proc/self/fd/") && File(modelPath).exists() && File(modelPath).canRead()) {
            return modelPath
        }

        if (modelUri != null) {
            val pfd = try {
                context.contentResolver.openFileDescriptor(modelUri, "r")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed opening FileDescriptor for $modelUri: ${e.message}", e)
                null
            }

            if (pfd != null) {
                pfd.use { descriptor ->
                    val fd = descriptor.fd
                    val procFdPath = "/proc/self/fd/$fd"

                    try {
                        val canonical = android.system.Os.readlink(procFdPath)
                        if (canonical != null && canonical.endsWith(".gguf", ignoreCase = true) && File(canonical).exists() && File(canonical).canRead()) {
                            return canonical
                        }
                    } catch (readlinkEx: Exception) {
                        AppLogger.d(TAG, "Could not resolve procFdPath via readlink: ${readlinkEx.message}")
                    }
                }
            }

            return modelUri.toString()
        }

        return modelPath?.takeIf { File(it).exists() }
    }

    fun resolveMmprojFilePath(context: Context, path: String?, uri: Uri?): String? {
        if (path != null && File(path).exists() && File(path).canRead()) {
            return path
        }
        if (uri != null) {
            val pfd = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed opening FileDescriptor for mmproj $uri: ${e.message}", e)
                null
            }
            if (pfd != null) {
                pfd.use { descriptor ->
                    val fd = descriptor.fd
                    val procFdPath = "/proc/self/fd/$fd"
                    try {
                        val canonical = android.system.Os.readlink(procFdPath)
                        if (canonical != null && File(canonical).exists() && File(canonical).canRead()) {
                            return canonical
                        }
                    } catch (readlinkEx: Exception) {
                        AppLogger.d(TAG, "Could not resolve mmproj procFdPath via readlink: ${readlinkEx.message}")
                    }
                }
            }
            return uri.toString()
        }
        return null
    }

    fun openDetachedFd(context: Context, path: String?, uri: Uri?): Int {
        if (uri != null) {
            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    val fd = pfd.detachFd()
                    AppLogger.d(TAG, "Opened detached FD $fd from URI: $uri")
                    return fd
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed opening detached FD from URI $uri: ${e.message}", e)
            }
        }
        if (path != null) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val fd = pfd.detachFd()
                    AppLogger.d(TAG, "Opened detached FD $fd from file: $path")
                    return fd
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed opening detached FD from path $path: ${e.message}", e)
            }
        }
        return -1
    }

    fun safelyCloseDetachedFd(fd: Int) {
        if (fd < 0) return
        try {
            // Verify via kernel procfs that the FD is currently open in this process.
            // If librnllama or another native component already closed it, /proc/self/fd/$fd
            // will not exist. Attempting to adopt and close an already-closed FD triggers
            // fatal fdsan SIGABRT on Android 11+ (API 30+).
            val procFd = File("/proc/self/fd/$fd")
            if (!procFd.exists()) {
                AppLogger.d(TAG, "Detached FD $fd was already closed by native runtime, skipping close.")
                return
            }
            ParcelFileDescriptor.adoptFd(fd).close()
            AppLogger.d(TAG, "Safely closed detached FD $fd")
        } catch (t: Throwable) {
            AppLogger.d(TAG, "Detached FD $fd was already closed or invalid: ${t.message}")
        }
    }

    fun inspectFileHeader(filePath: String): ModelFileType {
        return try {
            val file = File(filePath)
            if (file.exists() && file.canRead()) {
                val buffer = ByteArray(16)
                file.inputStream().use { it.read(buffer) }
                ModelFileType.detect(file.name, buffer)
            } else {
                ModelFileType.detect(filePath)
            }
        } catch (inspectEx: Exception) {
            AppLogger.d(TAG, "Header inspection error for $filePath: ${inspectEx.message}")
            ModelFileType.detect(filePath)
        }
    }
}
