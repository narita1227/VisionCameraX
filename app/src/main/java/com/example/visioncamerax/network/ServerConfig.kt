package com.example.visioncamerax.network

import android.os.Build
import com.example.visioncamerax.BuildConfig

object ServerConfig {

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
            "google_sdk" == Build.PRODUCT
    }

    private fun selectedHost(): String {
        val overrideHost = BuildConfig.SERVER_HOST_OVERRIDE.trim()
        if (overrideHost.isNotEmpty()) {
            return overrideHost
        }

        return if (isEmulator()) {
            BuildConfig.SERVER_EMULATOR_HOST
        } else {
            BuildConfig.SERVER_REAL_DEVICE_HOST
        }
    }

    fun wsUrl(): String = "ws://${selectedHost()}:${BuildConfig.SERVER_WS_PORT}/ws/status"

    fun videoWsUrl(): String = "ws://${selectedHost()}:${BuildConfig.SERVER_WS_PORT}/ws/video"
}
