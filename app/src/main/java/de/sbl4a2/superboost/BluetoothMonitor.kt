package de.sbl4a2.superboost

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Zeigt das aktuell verbundene Bluetooth-Audio-Ausgabegerät an (AudioManager, stabile API).
 * Aktualisiert sich automatisch, wenn Geräte verbunden/getrennt werden.
 */
class BluetoothMonitor(context: Context, private val onChange: (Info?) -> Unit) {

    data class Info(val name: String, val kind: String, val looksLikeSilverCrest: Boolean)

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    fun start() {
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    fun refresh() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { isBluetooth(it.type) }
        val best = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?: devices.firstOrNull()
        if (best == null) {
            onChange(null)
            return
        }
        val name = best.productName?.toString()?.takeIf { n -> n.isNotBlank() } ?: "Bluetooth-Gerät"
        val kind = when (best.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth-Audio (A2DP)"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (Telefonie, SCO)"
            else -> "Bluetooth LE Audio"
        }
        val lower = name.lowercase()
        onChange(Info(name, kind, lower.contains("sbl") || lower.contains("silvercrest")))
    }

    private fun isBluetooth(type: Int): Boolean {
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true
        if (Build.VERSION.SDK_INT >= 31 &&
            (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
        ) return true
        return false
    }
}
