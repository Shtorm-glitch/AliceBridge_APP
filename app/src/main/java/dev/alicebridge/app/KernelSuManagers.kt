package dev.alicebridge.app

import android.content.Context
import java.io.File

data class KernelSuManager(val packageName: String, val label: String)

object KernelSuManagers {
    fun installed(context: Context): List<KernelSuManager> = context.packageManager.getInstalledApplications(0)
        .filter { it.nativeLibraryDir != null && File(it.nativeLibraryDir, "libksud.so").isFile }
        .map { KernelSuManager(it.packageName, context.packageManager.getApplicationLabel(it).toString()) }
        .sortedBy { it.label }

    fun available(context: Context, packageName: String): Boolean = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        info.nativeLibraryDir != null && File(info.nativeLibraryDir, "libksud.so").isFile
    } catch (_: Exception) {
        false
    }
}
