package de.sbl4a2.superboost

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.transition.TransitionManager
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import de.sbl4a2.superboost.databinding.ActivityMainBinding
import java.util.Locale
import kotlin.math.log10
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class Mode { SYSTEM, PLAYER }

    companion object {
        /** Bis hierhin ohne Bestätigung; darüber erst nach ausdrücklicher Zustimmung. */
        private const val SAFE_LIMIT = 150
    }

    private lateinit var b: ActivityMainBinding
    private val engine = BoostEngine
    private lateinit var playback: PlaybackController
    private lateinit var bluetooth: BluetoothMonitor

    private var mode = Mode.SYSTEM
    private var highGainConfirmed = false
    private var highGainDialogOpen = false
    private var askedRecordPermission = false
    private var chipSuppress = false
    private var started = false
    private var bannerLevel = 0
    private var clipUntil = 0L
    private var pickedName: String? = null
    private var glowAnimator: ObjectAnimator? = null

    private val bandSliders = ArrayList<Slider>()
    private val sliderAnimators = HashMap<Slider, ValueAnimator>()

    private val cText by lazy { ContextCompat.getColor(this, R.color.text) }
    private val cAmber by lazy { ContextCompat.getColor(this, R.color.amber) }
    private val cRed by lazy { ContextCompat.getColor(this, R.color.red) }
    private val cMuted by lazy { ContextCompat.getColor(this, R.color.muted) }
    private val presetChipIds by lazy { listOf(b.chipNormal.id, b.chipBass.id, b.chipClear.id, b.chipExtra.id) }

    // ---------------------------------------------------------------- Berechtigungen / Auswahl
    private val permRecord = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) engine.setMonitor(monitor)
        else b.tvMonitorInfo.text = "Echtzeit-Anzeige aus (keine Berechtigung). Alle Effekte funktionieren trotzdem."
    }
    private val permBluetooth = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        bluetooth.refresh()
    }
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedName = displayName(uri)
            startPlayback(uri)
        }
    }

    // ---------------------------------------------------------------- Echtzeit-Anzeige
    private val monitor = object : BoostEngine.MonitorListener {
        override fun onData(levels: FloatArray, peak: Float, clipping: Boolean) {
            b.spectrum.submit(levels)
            val db = if (peak > 0.001f) 20f * log10(peak) else -60f
            b.tvPeak.text = String.format(Locale.GERMANY, "Spitzenpegel ca. %.0f dBFS", db)
            if (clipping) clipUntil = SystemClock.uptimeMillis() + 1500
            val show = SystemClock.uptimeMillis() < clipUntil
            if (b.tvClip.isVisible != show) b.tvClip.isVisible = show
            b.tvMonitorInfo.text = "Echtzeit-Anzeige aktiv (Android-Visualizer)"
        }

        override fun onMonitorError(message: String) {
            b.tvMonitorInfo.text = message
            b.spectrum.clear()
        }
    }

    // ================================================================= Lebenszyklus
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        playback = PlaybackController(this)
        bluetooth = BluetoothMonitor(this) { info -> showDevice(info) }

        // Veraltete Session (z. B. nach Activity-Neustart) aufräumen
        if (engine.attachedSession > 0) engine.release()
        if (!engine.isAttached && engine.enabled) engine.setEnabled(false)
        engine.probe(this)

        playback.onStateChanged = {
            runOnUiThread {
                updatePlayUi()
                if (playback.isPlaying) engine.refreshMonitor()
            }
        }
        playback.onError = { msg ->
            runOnUiThread {
                if (mode == Mode.PLAYER) engine.release()
                updatePlayUi()
                showError("Wiedergabe nicht möglich", msg)
            }
        }
        engine.onPlayerVolume = { v -> playback.setVolume(v) }
        engine.onIdleDisabled = {
            if (mode == Mode.SYSTEM) engine.release()
            refreshStatus()
        }

        buildBandSliders()
        setupGain()
        setupToneSliders()
        setupPower()
        setupMode()
        setupPresets()
        setupPlayerButtons()
        setupReset()

        b.cardDevice.setOnClickListener { requestBluetoothPermissionIfNeeded(); bluetooth.refresh() }
        requestBluetoothPermissionIfNeeded()

        syncUiFromEngine()
        updateModeUi()
        updatePlayUi()
        refreshStatus()
        showStartupWarningIfUnsupported()
        playIntro()
    }

    override fun onStart() {
        super.onStart()
        started = true
        bluetooth.start()
        if (hasRecordPermission()) engine.setMonitor(monitor)
        updatePowerUi(animate = false)
    }

    override fun onStop() {
        super.onStop()
        started = false
        bluetooth.stop()
        engine.setMonitor(null)
        b.spectrum.clear()
        b.tvClip.isVisible = false
        // Kein Hintergrundbetrieb des eigenen Players
        if (mode == Mode.PLAYER) {
            playback.stop()
            engine.release()
        }
        updateGlow()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.onIdleDisabled = null
        engine.onPlayerVolume = null
        playback.onStateChanged = null
        playback.onError = null
        playback.stop()
        // Zurück-Taste / Beenden: Booster aus, keine Effekte im Hintergrund
        if (isFinishing) engine.shutdown()
    }

    // ================================================================= Einrichtung
    private fun setupGain() {
        b.sliderGain.setLabelFormatter { v -> "${v.toInt()} %" }
        b.sliderGain.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) {
                renderGain(value.toInt())
                return@addOnChangeListener
            }
            var v = value.toInt()
            if (v > SAFE_LIMIT && !highGainConfirmed) {
                v = SAFE_LIMIT
                b.sliderGain.value = SAFE_LIMIT.toFloat()
                askHighGain()
            }
            engine.setGainPercent(v)
            clearPresetSelection()
            renderGain(v)
        }
    }

    private fun setupToneSliders() {
        b.sliderBass.setLabelFormatter { v -> "${v.toInt()} %" }
        b.sliderTreble.setLabelFormatter { v -> String.format(Locale.GERMANY, "%+d %%", v.toInt()) }
        b.sliderBass.addOnChangeListener { _, v, fromUser ->
            b.tvBassValue.text = "${v.toInt()} %"
            if (fromUser) {
                engine.setBassPercent(v.toInt())
                clearPresetSelection()
                updateGainInfo()
            }
        }
        b.sliderTreble.addOnChangeListener { _, v, fromUser ->
            b.tvTrebleValue.text = String.format(Locale.GERMANY, "%+d %%", v.toInt())
            if (fromUser) {
                engine.setTreblePercent(v.toInt())
                clearPresetSelection()
                updateGainInfo()
            }
        }
        val caps = engine.capabilities
        val bassPossible = caps.bassStrength || engine.bandCount > 0
        b.sliderBass.isEnabled = bassPossible
        b.sliderTreble.isEnabled = engine.bandCount > 0
        b.tvToneInfo.text = when {
            !caps.probed -> ""
            caps.bassStrength -> "Bass: echter Bass-Boost-Effekt des Geräts. Höhen: Anhebung/Absenkung der oberen Equalizer-Bänder (Shelf-Näherung, max. ±6 dB)."
            engine.bandCount > 0 -> "Bass-Boost-Stärke wird vom Gerät nicht unterstützt: Bass wird über die tiefen Equalizer-Bänder nachgebildet (max. +6 dB). Höhen: obere Equalizer-Bänder (max. ±6 dB)."
            else -> "Weder Bass-Boost noch Equalizer verfügbar: Bass- und Höhenregler haben auf diesem Gerät keine Wirkung."
        }
    }

    private fun buildBandSliders() {
        b.bandContainer.removeAllViews()
        bandSliders.clear()
        val n = engine.bandCount
        if (n == 0) {
            b.tvEqInfo.text = "Dieses Gerät stellt keinen Equalizer bereit."
            return
        }
        for (i in 0 until n) {
            val row = layoutInflater.inflate(R.layout.item_band, b.bandContainer, false)
            val tvF = row.findViewById<TextView>(R.id.bandFreq)
            val tvV = row.findViewById<TextView>(R.id.bandValue)
            val s = row.findViewById<Slider>(R.id.bandSlider)
            tvF.text = formatFreq(engine.bandFreqsHz[i])
            s.valueFrom = engine.bandMinMb / 100f
            s.valueTo = engine.bandMaxMb / 100f
            s.stepSize = 0.5f
            s.value = (engine.bandUserMb[i] / 100f).coerceIn(s.valueFrom, s.valueTo)
            s.setLabelFormatter { v -> String.format(Locale.GERMANY, "%+.1f dB", v) }
            tvV.text = dbText(s.value)
            s.addOnChangeListener { _, value, fromUser ->
                tvV.text = dbText(value)
                if (fromUser) {
                    engine.setBandMb(i, (value * 100f).roundToInt())
                    clearPresetSelection()
                    updateGainInfo()
                }
            }
            bandSliders.add(s)
            b.bandContainer.addView(row)
        }
        b.tvEqInfo.text = "Bänder und Mittenfrequenzen stammen vom Gerät. Anhebungen werden automatisch teilweise von der Gesamtverstärkung abgezogen (Headroom gegen Clipping)."
    }

    private fun setupPower() {
        b.powerButton.setOnClickListener {
            if (engine.enabled) turnOff() else turnOn()
        }
    }

    private fun setupMode() {
        b.chipsMode.setOnCheckedStateChangeListener { _, ids ->
            if (ids.isEmpty()) return@setOnCheckedStateChangeListener
            setMode(if (ids.first() == b.chipPlayer.id) Mode.PLAYER else Mode.SYSTEM)
        }
    }

    private fun setupPresets() {
        b.chipsPresets.setOnCheckedStateChangeListener { _, ids ->
            if (chipSuppress || ids.isEmpty()) return@setOnCheckedStateChangeListener
            val idx = presetChipIds.indexOf(ids.first())
            if (idx >= 0) applyPreset(Presets.all[idx])
        }
    }

    private fun setupPlayerButtons() {
        b.btnPickFile.setOnClickListener { pickFile.launch(arrayOf("audio/*")) }
        b.btnTone.setOnClickListener { startPlayback(null) }
        b.btnStop.setOnClickListener { stopPlayback() }
    }

    private fun setupReset() {
        b.btnReset.setOnClickListener {
            applyPreset(Presets.all[0])
            chipSuppress = true
            b.chipsPresets.check(b.chipNormal.id)
            chipSuppress = false
        }
    }

    // ================================================================= Aktionen
    private fun turnOn() {
        if (mode == Mode.SYSTEM) {
            val r = engine.attach(0)
            if (!r.ok) {
                showError("Booster nicht möglich", r.message)
                refreshStatus()
                return
            }
        }
        engine.setEnabled(true)
        ensureMonitor()
        updatePowerUi(animate = true)
        updateGainInfo()
        refreshStatus()
    }

    private fun turnOff() {
        engine.setEnabled(false) // Effekte werden weich heruntergefahren und danach abgeschaltet
        updatePowerUi(animate = true)
        updateGainInfo()
        refreshStatus()
    }

    private fun setMode(newMode: Mode) {
        if (newMode == mode) return
        playback.stop()
        engine.release()
        mode = newMode
        if (mode == Mode.SYSTEM && engine.enabled) {
            val r = engine.attach(0)
            if (!r.ok) {
                engine.setEnabled(false)
                showError("Systemweiter Booster nicht möglich", r.message)
            }
        }
        updateModeUi()
        updatePowerUi(animate = false)
        updateGainInfo()
        refreshStatus()
    }

    /** Startet Datei (uri != null) oder Testton (uri == null) auf einer eigenen Audio-Session. */
    private fun startPlayback(uri: Uri?) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val sid = am.generateAudioSessionId()
        if (sid == AudioManager.ERROR) {
            showError("Audio-Session", "Es konnte keine Audio-Session erzeugt werden.")
            return
        }
        playback.stop()
        engine.release()
        val r = engine.attach(sid)
        if (!r.ok) {
            showError("Audioeffekte nicht verfügbar", r.message)
            refreshStatus()
            return
        }
        val ok = if (uri != null) playback.playFile(uri, pickedName ?: "Datei", sid) else playback.playTone(sid)
        if (!ok) engine.release()
        ensureMonitor()
        refreshStatus()
    }

    private fun stopPlayback() {
        playback.stop()
        if (mode == Mode.PLAYER) engine.release()
        refreshStatus()
    }

    private fun applyPreset(p: Presets.Preset) {
        var gain = p.gainPercent
        if (gain > SAFE_LIMIT && !highGainConfirmed) gain = SAFE_LIMIT
        engine.setGainPercent(gain)
        engine.setBassPercent(p.bassPercent)
        engine.setTreblePercent(p.treblePercent)
        for (i in 0 until engine.bandCount) {
            engine.setBandMb(i, (p.curveDb(engine.bandFreqsHz[i]) * 100f).roundToInt())
        }
        animateSlider(b.sliderGain, gain.toFloat())
        animateSlider(b.sliderBass, p.bassPercent.toFloat())
        animateSlider(b.sliderTreble, p.treblePercent.toFloat())
        for (i in bandSliders.indices) animateSlider(bandSliders[i], engine.bandUserMb[i] / 100f)
        updateGainInfo()
    }

    private fun askHighGain() {
        if (highGainDialogOpen) return
        highGainDialogOpen = true
        MaterialAlertDialogBuilder(this)
            .setTitle("Sehr hohe Verstärkung")
            .setMessage(
                "Über 150 % steigt das Risiko für Verzerrungen, Gehörschäden und Schäden am Lautsprecher deutlich. " +
                    "Die App fährt Änderungen langsam an, kann aber nicht verhindern, dass Material verzerrt klingt.\n\n" +
                    "Erhöhe den Regler nur schrittweise und achte auf die Anzeige \"CLIPPING-GEFAHR\"."
            )
            .setNegativeButton("Bei 150 % bleiben", null)
            .setPositiveButton("Trotzdem erlauben") { _, _ -> highGainConfirmed = true }
            .setOnDismissListener { highGainDialogOpen = false }
            .show()
    }

    // ================================================================= UI-Hilfen
    private fun syncUiFromEngine() {
        b.sliderGain.value = engine.gainPercent.toFloat()
        b.sliderBass.value = engine.bassPercent.toFloat()
        b.sliderTreble.value = engine.treblePercent.toFloat()
        b.tvBassValue.text = "${engine.bassPercent} %"
        b.tvTrebleValue.text = String.format(Locale.GERMANY, "%+d %%", engine.treblePercent)
        highGainConfirmed = engine.gainPercent > SAFE_LIMIT
        renderGain(engine.gainPercent)
        updatePowerUi(animate = false)
    }

    private fun renderGain(v: Int) {
        b.tvGain.text = "$v%"
        val color = when {
            v <= 100 -> cText
            v <= SAFE_LIMIT -> ColorUtils.blendARGB(cText, cAmber, (v - 100) / 50f)
            else -> ColorUtils.blendARGB(cAmber, cRed, (v - SAFE_LIMIT) / 50f)
        }
        b.tvGain.setTextColor(color)
        updateBanner(if (v > SAFE_LIMIT) 2 else if (v > 120) 1 else 0)
        updateGainInfo()
    }

    private fun updateBanner(level: Int) {
        if (level == bannerLevel) return
        TransitionManager.beginDelayedTransition(b.content)
        bannerLevel = level
        when (level) {
            0 -> b.tvWarning.isVisible = false
            1 -> {
                b.tvWarning.setBackgroundResource(R.drawable.bg_banner_warn)
                b.tvWarning.text = "⚠ Erhöhte Verstärkung: Je nach Material kann der Klang verzerren. Lautstärke langsam erhöhen."
                b.tvWarning.isVisible = true
            }
            else -> {
                b.tvWarning.setBackgroundResource(R.drawable.bg_banner_danger)
                b.tvWarning.text = "⚠ HOHE VERSTÄRKUNG: Gefahr von Verzerrungen, Gehörschäden und Lautsprecherschäden. Nur schrittweise erhöhen und Abstand zum Lautsprecher halten."
                b.tvWarning.isVisible = true
            }
        }
    }

    private fun updateGainInfo() {
        val v = engine.gainPercent
        b.tvGainInfo.text = when {
            !engine.enabled -> "Booster aus: Originalpegel, alle Effekte neutral."
            mode == Mode.SYSTEM && v < 100 ->
                "Unter 100 %: Systemweit wird nicht abgesenkt. Nutze die Hardware-Lautstärketasten (oder den Eigenen Player)."
            else -> String.format(
                Locale.GERMANY,
                "Zielverstärkung: %+.1f dB (nach Headroom-Abzug durch EQ/Bass). Änderungen werden weich angefahren.",
                engine.targetGainDb()
            )
        }
    }

    private fun updatePowerUi(animate: Boolean) {
        val on = engine.enabled
        if (b.powerButton.isActivated != on) {
            b.powerButton.isActivated = on
            if (animate) {
                b.powerButton.animate().cancel()
                b.powerButton.animate().scaleX(0.86f).scaleY(0.86f).setDuration(90).withEndAction {
                    b.powerButton.animate().scaleX(1f).scaleY(1f).setDuration(280)
                        .setInterpolator(OvershootInterpolator(3f)).start()
                }.start()
            }
        }
        b.tvGainLabel.text = if (on) "VERSTÄRKUNG · AKTIV" else "VERSTÄRKUNG · AUS"
        b.tvGain.animate().alpha(if (on) 1f else 0.45f).setDuration(250).start()
        updateGlow()
    }

    private fun updateGlow() {
        val on = engine.enabled && started
        if (on) {
            if (glowAnimator == null) {
                glowAnimator = ObjectAnimator.ofPropertyValuesHolder(
                    b.powerGlow,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.3f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.3f),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0.7f, 0f)
                ).apply {
                    duration = 1400
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    start()
                }
            }
        } else {
            glowAnimator?.cancel()
            glowAnimator = null
            b.powerGlow.alpha = 0f
        }
    }

    private fun updateModeUi() {
        val player = mode == Mode.PLAYER
        b.playerControls.isVisible = player
        b.tvModeInfo.text = if (player) {
            "Eigener Player: Effekte hängen an einer eigenen Audio-Session. Das funktioniert auf den meisten Geräten und ist sofort hörbar. Mit Testton oder eigener Datei prüfen. Wiedergabe stoppt, sobald die App nicht mehr sichtbar ist."
        } else {
            "Systemweit: Effekte werden an den globalen Ausgabe-Mix gehängt (Session 0). Viele Geräte und Apps ignorieren das, dann hörst du keinen Unterschied. Dann bitte den Eigenen Player nutzen."
        }
    }

    private fun updatePlayUi() {
        b.btnStop.isEnabled = playback.isPlaying || playback.nowPlaying != null
        b.tvNowPlaying.text = playback.nowPlaying?.let { "▶ $it" } ?: "Nichts abgespielt. Tipp: mit dem Testton und niedriger Systemlautstärke beginnen."
    }

    private fun clearPresetSelection() {
        if (b.chipsPresets.checkedChipId != View.NO_ID) {
            chipSuppress = true
            b.chipsPresets.clearCheck()
            chipSuppress = false
        }
    }

    private fun showDevice(info: BluetoothMonitor.Info?) {
        if (info == null) {
            b.tvDevice.text = "Kein Bluetooth-Gerät verbunden"
            b.tvDeviceInfo.text = "Ausgabe über eingebauten Lautsprecher, Kopfhörer oder anderen Ausgang. Der Booster wirkt auf den aktuellen Ausgang."
            b.deviceDot.backgroundTintList = ColorStateList.valueOf(cMuted)
        } else {
            b.tvDevice.text = info.name
            var text = info.kind
            if (info.looksLikeSilverCrest) text += " · SilverCrest-Lautsprecher erkannt"
            if (needsBluetoothPermission()) {
                text += "\nTippen, um \"Geräte in der Nähe\" zu erlauben (für den echten Gerätenamen)."
            }
            b.tvDeviceInfo.text = text
            b.deviceDot.backgroundTintList = ColorStateList.valueOf(cRed)
        }
    }

    private fun refreshStatus() {
        val c = engine.capabilities
        val sb = StringBuilder()
        fun line(name: String, ok: Boolean, extra: String = "") {
            sb.append(if (ok) "✓ " else "✗ ").append(name).append(extra).append('\n')
        }
        line("Lautstärke-Verstärker (LoudnessEnhancer)", c.loudness)
        line("Equalizer", c.equalizer, if (c.equalizer) " (${engine.bandCount} Bänder)" else "")
        line(
            "Bass Boost", c.bassBoost,
            when {
                c.bassStrength -> " (Gerätefunktion)"
                c.bassBoost || engine.bandCount > 0 -> " (über Equalizer nachgebildet)"
                else -> ""
            }
        )
        sb.append("\nModus: ").append(if (mode == Mode.SYSTEM) "Systemweit (Session 0)" else "Eigener Player").append('\n')
        sb.append("Effekte aktiv: ").append(if (engine.isAttached && engine.enabled) "ja" else "nein")
        if (engine.isAttached) sb.append(" (Session ${engine.attachedSession})")
        engine.lastWarning?.let { sb.append("\n\n⚠ ").append(it) }
        b.tvStatus.text = sb.toString().trim()
    }

    private fun showStartupWarningIfUnsupported() {
        val c = engine.capabilities
        if (c.probed && !c.equalizer && !c.bassBoost && !c.loudness) {
            showError(
                "Audioeffekte nicht unterstützt",
                "Dieses Gerät stellt keine der benötigten Android-Audioeffekte (Equalizer, Bass Boost, LoudnessEnhancer) bereit. " +
                    "Auch über Bluetooth lässt sich der Klang dann per Software nicht verändern. " +
                    "Die App kann hier keine Wirkung erzielen."
            )
        }
    }

    private fun showError(title: String, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun playIntro() {
        val cards = listOf(b.cardDevice, b.cardMain, b.cardSource, b.cardPresets, b.cardEq, b.cardTone, b.cardStatus)
        cards.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 60f
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(60L * i).setDuration(450)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
    }

    // ================================================================= Slider-Animation
    private fun animateSlider(s: Slider, to: Float) {
        sliderAnimators[s]?.cancel()
        val target = snap(s, to)
        val a = ValueAnimator.ofFloat(s.value, target)
        a.duration = 380
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener { s.value = snap(s, it.animatedValue as Float) }
        sliderAnimators[s] = a
        a.start()
    }

    private fun snap(s: Slider, v: Float): Float {
        val step = if (s.stepSize > 0f) s.stepSize else 1f
        val n = Math.round((v - s.valueFrom) / step)
        return (s.valueFrom + n * step).coerceIn(s.valueFrom, s.valueTo)
    }

    // ================================================================= Berechtigungen
    private fun hasRecordPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensureMonitor() {
        if (hasRecordPermission()) {
            engine.setMonitor(monitor)
        } else if (!askedRecordPermission) {
            askedRecordPermission = true
            b.tvMonitorInfo.text = "Echtzeit-Anzeige aus (keine Berechtigung)."
            MaterialAlertDialogBuilder(this)
                .setTitle("Echtzeit-Anzeige")
                .setMessage(
                    "Für Spektrum und Pegel nutzt Android die Visualizer-API. Sie verlangt formal die Mikrofon-Berechtigung, " +
                        "wertet aber nur die Wiedergabe aus. Die App nimmt nichts auf und speichert nichts.\n\n" +
                        "Ohne Berechtigung funktionieren alle Effekte weiter, nur die Anzeige bleibt leer."
                )
                .setPositiveButton("Erlauben") { _, _ -> permRecord.launch(Manifest.permission.RECORD_AUDIO) }
                .setNegativeButton("Nein danke", null)
                .show()
        }
    }

    private fun needsBluetoothPermission() =
        Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED

    private fun requestBluetoothPermissionIfNeeded() {
        if (needsBluetoothPermission()) permBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    // ================================================================= Formatierung
    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (e: Exception) { }
        return "Datei"
    }

    private fun formatFreq(hz: Int): String = when {
        hz < 1000 -> "$hz Hz"
        hz % 1000 == 0 -> "${hz / 1000} kHz"
        else -> String.format(Locale.GERMANY, "%.1f kHz", hz / 1000f)
    }

    private fun dbText(v: Float) = String.format(Locale.GERMANY, "%+.1f dB", v)
}
