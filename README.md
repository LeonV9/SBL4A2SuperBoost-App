# SBL 4 A2 SUPER BOOST

Native Android-App (Kotlin, minSdk 26 / Android 8.0, targetSdk 34) zur **softwareseitigen** Klangverstärkung der
Smartphone-Ausgabe, z. B. für Bluetooth-Lautsprecher wie den SilverCrest SBL 4 A2.
Kein Root, keine kostenpflichtigen APIs, kein Hintergrunddienst.

> **Status:** Der Code wurde in der Erstellungsumgebung weder kompiliert noch auf einem Gerät getestet
> (dort gab es keinen Android-SDK-/Gradle-Zugang). Bitte beim ersten Build Fehlermeldungen prüfen und die
> Testschritte unten durchführen, bevor du dich auf das Ergebnis verlässt.

## Funktionen
- Verstärkung 0–200 % (100 % = Original) mit großem Regler und Ein/Aus-Taste
- Equalizer (Bänder und Frequenzen kommen vom Gerät), Bass Boost, Höhenregler
- Presets: Normal, Bass, Clear Sound, Extra Boost
- Anzeige des verbundenen Bluetooth-Audiogeräts (live, auch bei Verbinden/Trennen)
- Echtzeit-Spektrum und Spitzenpegel inkl. "CLIPPING-GEFAHR"-Hinweis
- Geräte-Check mit verständlichen Fehlermeldungen, wenn Effekte nicht unterstützt werden
- Schwarz/Rot-Design, Animationen (Intro, Preset-Übergänge, pulsierender Power-Button, Spektrum)

## Technik (nur stabile Plattform-APIs, `android.media.audiofx`)
| Funktion | API |
|---|---|
| Verstärkung | `LoudnessEnhancer.setTargetGain` (100 % = 0 dB, 200 % = +6,02 dB; harte Obergrenze 6,2 dB) |
| Equalizer | `Equalizer` (Bänder per `getNumberOfBands`/`getCenterFreq`/`getBandLevelRange`) |
| Bass | `BassBoost` (falls Stärke unterstützt), sonst Nachbildung über tiefe EQ-Bänder |
| Höhen | Shelf-Näherung über obere EQ-Bänder (±6 dB) |
| Bluetooth-Anzeige | `AudioManager.getDevices` + `AudioDeviceCallback` |
| Pegel/Spektrum | `Visualizer` (benötigt formal `RECORD_AUDIO`, nimmt aber nichts auf) |
| Testton / Datei | `AudioTrack` / `MediaPlayer` mit eigener Audio-Session |

### Zwei Modi
1. **Eigener Player (zuverlässig):** Effekte hängen an der Audio-Session des integrierten Players
   (Datei oder Testton). Das ist der Weg, der auf den meisten Geräten tatsächlich wirkt.
2. **Systemweit (experimentell):** Effekte werden an Session 0 (globaler Mix) gehängt. Android hat
   dieses Verfahren eingeschränkt; viele Geräte und Apps (Spotify, YouTube …) ignorieren es.
   Du siehst sofort im Geräte-Check/Gehör, ob es bei dir funktioniert.

## Sicherheitskonzept
- Weiches Anfahren (Slew-Limiting, 20-ms-Takt): Verstärkung steigt max. ~3 dB/s, fällt max. ~10 dB/s;
  EQ/Bass werden ebenfalls gerampt; beim Einschalten Start bei 0 dB; Player-Lautstärke mit Soft-Start.
- Warnbanner ab >120 % (gelb) und >150 % (rot); über 150 % nur nach ausdrücklicher Bestätigung.
- Headroom: EQ-/Bass-Anhebungen ziehen automatisch die Hälfte der größten Anhebung von der Verstärkung ab.
- Harte Obergrenze der Verstärkung (+6,2 dB); EQ-Anhebung pro Band max. +9 dB.
- Clipping-Anzeige über den Visualizer (grobe 8-Bit-Messung, nur Hinweis, keine Regelung).
- Beim Beenden mit der Zurück-Taste wird der Booster deaktiviert und alle Effekte werden freigegeben.
  Der Eigene Player stoppt, sobald die App nicht mehr sichtbar ist.

## Ehrliche Einschränkungen
- **Physikalische Grenzen bleiben.** Die App kann Schalldruck, Membranhub, Verstärker und den internen
  Begrenzer/DSP des Lautsprechers nicht überwinden. Mehr "Lautstärke" bedeutet vor allem höhere
  Lautheit durch Verstärkung plus Kompression (LoudnessEnhancer) und Klangformung. Bei Material, das
  schon laut gemastert ist, bringt das wenig und verzerrt schneller.
- **Wirksamkeit ist geräteabhängig.** Hersteller dürfen Effekte blockieren oder umgehen
  (Hardware-Offload, eigene Soundsysteme). Dann meldet die App das; ein Hörunterschied lässt sich nicht erzwingen.
- **Systemweit (Session 0) ist unzuverlässig** (siehe oben). Fremd-Apps lassen sich nicht garantiert beeinflussen.
- **Kompressor/Limiter:** Wie gut der LoudnessEnhancer Spitzen begrenzt, ist Sache der Geräte-Implementierung.
  Bei hohen Werten kann Clipping auftreten.
- **Unter 100 %:** Systemweit wird nicht abgesenkt (Hardware-Tasten nutzen); im Eigenen Player wirkt der Regler als Lautstärke.
- **Höhenregler** ist eine Näherung über die oberen Equalizer-Bänder, kein echter Shelf-Filter.
- **Bluetooth:** Die Effekte laufen im Smartphone, bevor das Signal kodiert und gesendet wird. Die Lautstärke am
  Lautsprecher selbst (und eine ggf. aktive "absolute Lautstärke") bleibt davon getrennt. Anzeigename kann
  ab Android 12 ohne die Berechtigung "Geräte in der Nähe" generisch sein.
- **Mikrofon-Berechtigung:** nur für die Anzeige (Visualizer), optional. Ohne sie funktionieren die Effekte weiter.
- Kein Hintergrunddienst: Systemweit kann Android den App-Prozess im Hintergrund beenden; dann enden die Effekte.

## APK erstellen
### Mit Android Studio (empfohlen)
1. Android Studio (Koala oder neuer, JDK 17 ist enthalten) installieren, **Open** → Ordner `SBL4A2SuperBoost`.
2. Warten, bis der Gradle-Sync fertig ist (Internet nötig, lädt Gradle 8.7, AGP 8.5.2, Material 1.12).
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**. Fertige Datei:
   `app/build/outputs/apk/debug/app-debug.apk`
4. Für eine signierte Release-APK: **Build → Generate Signed Bundle / APK → APK**, Keystore anlegen
   (Passwörter und .jks sicher aufbewahren, nicht ins Repository).

### Per Kommandozeile
Der Gradle-Wrapper-JAR liegt nicht bei (Binärdatei). Einmalig erzeugen, falls `gradle` installiert ist:
```
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
```
Signierte Release-APK:
```
./gradlew assembleRelease
zipalign -v 4 app/build/outputs/apk/release/app-release-unsigned.apk app-aligned.apk
apksigner sign --ks mein.jks --out SBL4A2SuperBoost.apk app-aligned.apk
```

### Installation
USB-Debugging aktivieren und `adb install -r app-debug.apk`, oder die APK aufs Handy kopieren und
(nach Freigabe "Aus dieser Quelle installieren") antippen.

## Automatischer Build mit GitHub Actions
1. Inhalt dieses Projekts (Ordner `.github`, `app`, `gradle`, Dateien im Hauptverzeichnis) in ein neues GitHub-Repository hochladen (Branch `main`).
2. Reiter **Actions** öffnen: Der Workflow **Build Debug APK** startet automatisch bei jedem Push (oder manuell über **Run workflow**).
3. Nach dem Durchlauf im Lauf unter **Artifacts** die Datei `SBL4A2-SuperBoost-debug-apk` herunterladen, entpacken und `app-debug.apk` auf dem Handy installieren.

Der Workflow nutzt JDK 17 und Gradle 8.7 (per `gradle/actions/setup-gradle`), daher wird keine `gradle-wrapper.jar` benötigt. Das Android-SDK ist auf `ubuntu-latest` vorinstalliert.

## Testplan (bitte durchführen)
1. **Geräte-Check** (unten in der App): Welche Effekte meldet dein Gerät?
2. Modus **Eigener Player** → **Testton** (Systemlautstärke niedrig starten!) → Booster einschalten →
   Verstärkung auf 120 %, dann 150 %: Pegel muss hörbar steigen, das Spektrum sich verändern.
3. Equalizer-Band oder Bass bewegen: Klangänderung muss hörbar sein. Booster aus/an: Vergleich (A/B).
4. Optional messen: Pegelmesser-App (z. B. mit Referenz im festen Abstand) – Unterschied dokumentieren.
5. **Systemweit** → einschalten, Musik in einer anderen App starten: Tut sich etwas? Wenn nicht, ist das
   auf diesem Gerät nicht unterstützt (normal).
6. Mit SBL 4 A2 verbinden: Gerätename erscheint oben; Test wiederholen, auf Verzerrung achten.

## Projektstruktur
```
app/src/main/java/de/sbl4a2/superboost/
  MainActivity.kt        UI, Modi, Presets, Warnungen, Berechtigungen
  BoostEngine.kt         Effekte, Ramping, Headroom, Visualizer
  PlaybackController.kt  Eigener Player (Datei/Testton)
  BluetoothMonitor.kt    Anzeige des Bluetooth-Ausgabegeräts
  SpectrumView.kt        Spektrum-Anzeige
  Presets.kt             Preset-Definitionen
app/src/main/res/        Layouts, Farben, Themes, Icons
```
