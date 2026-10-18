# MPS Diag

An Android diagnostics app for Mitsubishi diesels (4N15 / 4N14: Pajero Sport III, Triton/L200,
Outlander, Delica D:5) using a cheap ELM327 OBD adapter.

Current features:

- **DPF**: live data (33 values: soot load, temperatures, differential pressure, regeneration
  state and time remaining, counters, warning lights), **forced regeneration**, and the
  **DPF service resets** (DPF replaced, pressure sensor replaced, malfunction service).
- **Engine oil**: estimated oil level / consumption / fuel dilution, and the **oil service reset**
  that clears P252F after an oil change.
- **Fault codes**: read and clear codes in each module (engine, transmission, ABS, SRS, ETACS,
  A/C, 4WD, EPB, cluster, …), "scan all modules", and generic OBD-II.
- **Adapter log**: raw traffic, shareable, for troubleshooting.

Adapters: classic Bluetooth (most ELM327 / vLinker), Bluetooth LE (Vgate iCar, OBDLink CX, …)
and Wi-Fi (default `192.168.0.10:35000`). Requires Android 6.0 or newer.

## Install on your phone

1. Copy `mps-diag.apk` to the phone and open it.
2. Allow "install unknown apps" for the app you opened it with (Files, Chrome, …) when asked.
3. Pair your Bluetooth adapter in Android's Bluetooth settings first (PIN is usually `1234` or
   `0000`). BLE and Wi-Fi adapters don't need pairing.

## Using it

1. Ignition ON (engine running for regeneration), adapter plugged in.
2. Tap **Connect**, choose Bluetooth or Wi-Fi, and pick the adapter.
3. Open a feature from the home screen.

**Forced regeneration** – the screen shows live data first; the button enables once the ECU
answers. The engine type is guessed from the VIN; confirm it. Follow the on-screen instructions:
on the **4N15** it runs at idle while parked (exhaust ~600 °C – outdoors, nothing flammable
near the exhaust); on the **4N14** it is done while driving (3rd gear, ≥ 40 km/h, ≥ 2000 rpm).
The request is sent exactly once; if the ECU refuses, the reason is shown (for example
"conditions not correct" – fix the cause, don't just retry). Regeneration ends by itself
(about 25 min on the 4N15, or press the accelerator to cancel). Watch "DPF regeneration
operating" and "Regeneration time remaining".

**Oil service reset** – ignition ON, engine OFF, right after an oil change if P252F appears.

**Clear fault codes** – codes for faults that are still present come straight back; the app reads
the module again after clearing so you can see that.

> Use at your own risk. These commands change ECU state. Read fault codes first and fix real
> faults before forcing a regeneration.

Not yet supported: USB adapters, and the K-line SRS module on the older Pajero Sport (KH) / L200.

## Restyling

All styling is in resources, so most changes don't touch code:

| What | Where |
|---|---|
| Colours (brand, background, text, status) | `app/src/main/res/values/colors.xml` |
| Theme, button/card/text styles | `app/src/main/res/values/themes.xml` |
| Spacing, corner radius, text sizes | `app/src/main/res/values/dimens.xml` |
| Card / tile shapes | `app/src/main/res/drawable/bg_card.xml`, `bg_tile.xml` |
| Screen layouts | `app/src/main/res/layout/*.xml` |
| App icon | `app/src/main/res/drawable/ic_launcher_foreground.xml`, `mipmap*/ic_launcher.xml` |
| Texts | `app/src/main/res/values/strings.xml` |

The UI uses only the Android framework (no AndroidX), so the project builds without Google's
Maven repository.

## Building

**Android Studio**: open the project folder and run the `app` configuration. Both build paths
sign with `signing/debug.keystore`, so an APK from either installs over the other.

**Command line, no Gradle** (how the APK here was built):

```sh
./tools/build.sh   # -> build/mps-diag.apk
./tools/test.sh    # protocol tests against a simulated adapter + car
```

`build.sh` needs `aapt2`, `zipalign`, `apksigner` (Debian/Ubuntu packages of the same names),
`javac`, a Kotlin compiler (`KOTLIN_HOME`), `r8.jar` (`R8_JAR`) and an `android.jar` for
platform 34 (`ANDROID_JAR`). See the top of the script for defaults.

Bump `versionCode` / `versionName` in `app/build.gradle.kts` for each release; both build paths
read them from there.

## Code map

| Package | Contents |
|---|---|
| `transport` | Bluetooth classic (SPP), BLE and Wi-Fi links to the adapter |
| `elm` | ELM327 command/reply handling |
| `diag` | CAN diagnostic requests, ISO-TP reassembly, modules, DPF / oil / fault-code services |
| `ui` | Activities and the device picker |
| `Session` | The single connection; all adapter I/O runs on one background thread |

## Protocol notes

ISO 15765-4 CAN, 11-bit, 500 kbit/s. Each module is addressed with its request/response CAN ids
(see `diag/Module.kt`) and opened with the Mitsubishi extended session `10 92`.

| Function | Module | Request | Positive reply |
|---|---|---|---|
| ECU identification | Engine 7E0/7E8 | `1A 9C` | `5A 9C …` (bytes 3–6 `04 25 00 07` = DPF routines not supported) |
| VIN | Engine | `1A 90` | `5A 90` + 17 ASCII |
| DPF live data | Engine | `21 10/11/12/13/15/16/17/19/1F/4E/58/59/5D` | `61 xx …` (formulas in `diag/Dpf.kt`) |
| Forced regeneration | Engine | `30 50 07 FF` (4N15) / `30 60 07 FF` (4N14) | `70 …` |
| DPF service resets | Engine | `30 51/52/53 07 00` | `70 51/52/53 …` |
| Oil level data | Engine | `21 17` | bytes 2–4: value × 10 − 1000 cc |
| Oil service reset | Engine | `30 54 07 00` | `70 54 …` |
| Read fault codes | Any | `18 00 FF 00` | `58 N [hi lo status]×N` (status E0 current, 20 pending) |
| Clear fault codes | Any | `14 FF 00` | `54 …` (may answer `7F 14 78` "busy" first) |
| Generic OBD-II | 7DF/7E8 | `03` / `07` / `04` | `43` / `47` / `44` |
