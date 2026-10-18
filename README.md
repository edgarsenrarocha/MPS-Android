# MPS Scan Tool

An Android diagnostics app for Mitsubishi vehicles (Pajero Sport II/III, Pajero IV, Triton/L200,
Outlander, Delica D:5, Eclipse Cross, Xpander) using a cheap ELM327 OBD adapter.

Features:

- **Fault codes**: read and clear codes in each module (engine, transmission, ABS, SRS, ETACS,
  A/C, 4WD, EPB, cluster, …), "scan all modules", and generic OBD-II.
- **Engine**
  - **DPF**: 33 live values, **forced regeneration** (4N15 / 4N14) and the **DPF service resets**.
  - **Engine oil**: oil level / consumption / dilution and the **oil service reset** (P252F).
  - Live data: **diesel** (4D56 / 4M41 / 4N14 / 4N15, 85 values), **petrol 6B31** (37),
    **Eclipse Cross 4B40** (29), **Xpander 4A91** (55) and **generic OBD-II** (23).
- **Transmission & 4WD**: automatic transmission data (W6AJA, V4A5A/V5A5A, W8AWA/V8AWG – type
  detected automatically) and the Super Select 4WD II transfer case.
- **Brakes, steering & body**: ABS / ASC data, instrument cluster data and **service-reminder
  settings**.
- **Service functions**: fuel leak test, injector test, high-pressure pump valve
  initialisation, small injection quantity learning and learned values (with limits), battery
  current sensor calibration, A/T adaptation reset / INVECS-II stop, steering angle sensor
  calibration / initialisation, electric parking brake service position.
- **Connection test**: adapter, battery voltage, CAN bus, engine ECU (VIN, engine, ECU firmware,
  odometer, DPF support).
- **Adapter log**: raw traffic, shareable, for troubleshooting.

On every live screen, **Choose values** (menu) limits the list to what you need – fewer values
refresh faster – and **Choose type** switches engine / transmission variants where they apply.

Not included: TPMS sensor registration and injector code writing (they need the ECU's security
access), USB adapters, and the K-line SRS module of the older Pajero Sport (KH) / L200.

Adapters: classic Bluetooth (most ELM327 / vLinker), Bluetooth LE (Vgate iCar, OBDLink CX, …)
and Wi-Fi (default `192.168.0.10:35000`). Requires Android 6.0 or newer.

## Install on your phone

1. Copy `mps-scan-tool.apk` to the phone and open it.
2. Allow "install unknown apps" for the app you opened it with (Files, Chrome, …) when asked.
3. Pair your Bluetooth adapter in Android's Bluetooth settings first (PIN is usually `1234` or
   `0000`). BLE and Wi-Fi adapters don't need pairing.

## Using it

1. Plug the adapter in and turn the ignition ON (engine running for regeneration).
2. On the home screen tap **Connect** to open the **Connection** screen:
   - allow Bluetooth access / turn Bluetooth on if asked;
   - tap your adapter in the list (paired adapters are listed; tap **Scan for adapters** to find
     new or BLE ones) – or enter a Wi-Fi adapter's address;
   - after connecting, a **connection test** runs automatically: adapter → battery voltage →
     car CAN bus → engine ECU (VIN, engine, DPF support). Run it again any time with
     **Test connection**.
3. Go back and open a feature from the home screen.

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
| In-app icons | `app/src/main/res/drawable/ic_*.xml`, generated from `tools/icons.py` |
| Which icon each screen uses | `app/src/main/java/com/mpsdiag/ui/Icons.kt` |
| Texts | `app/src/main/res/values/strings.xml` |

The UI uses only the Android framework (no AndroidX), so the project builds without Google's
Maven repository.

## Building

**Android Studio**: open the project folder and run the `app` configuration. Both build paths
sign with `signing/debug.keystore`, so an APK from either installs over the other.

**Command line, no Gradle** (how the APK here was built):

```sh
./tools/build.sh   # -> build/mps-scan-tool.apk
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
