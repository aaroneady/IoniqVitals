# IoniqVitals

Android Auto companion app for the Hyundai Ioniq 5. Reads vehicle data from a Bluetooth LE
OBD-II dongle (OBDLink CX) and renders a **three-tile dashboard** — front lights, rear/brake
lights, and a consolidated stats tile (12V aux, coolant temp, SOH, SOC) — as a media-browse
grid you open from the Android Auto app list. The app is branded **Ioniq Vitals** on-device.

<img width="473" height="1024" alt="GitHub_Phone_Example" src="https://github.com/user-attachments/assets/2e02ab8a-1cc5-4e52-82c6-f00a7b6a8b36" /><img width="852" height="433" alt="GitHub_Dashboard_Example" src="https://github.com/user-attachments/assets/f1cdd4f6-31e6-42c6-812d-db68815576d2" />

## Surface: media-browse tile grid

The app surfaces through a classic `MediaBrowserService` (`IoniqVitalsMediaBrowserService`). When you
open **Ioniq Vitals** from Android Auto's media app launcher, the head unit browses this service
and lays the children out as a tile grid (`CONTENT_STYLE = GRID`):

- **Front lights** — a comic-book Ioniq 5 image, headlights on/off.
- **Rear lights** — a comic-book Ioniq 5 image, brake/regen lamp on/off.
- **Stats** — a single tile of left-justified text: `12V`, `Temp (°C/°F)`, `SOH`, `SOC`.

Tile images are served as **content-URI icons** by `TileImageProvider`, with the drawable id and a
`v` cache-buster baked into the URL. The head unit caches icons by URL, so a URL that changes with
state (e.g. lights on → off) is what forces it to re-fetch and actually update the tile. (Inline
`setIconBitmap` was tried and does **not** update on this head unit — it reads the bitmap once and
caches it; only an icon-URI change re-renders. The known trade-off is a brief press/"button-clicked"
flash on the playable cell during that re-fetch.)

The `MediaSession` is held **active only while OBD is connected** — the live dashboard the user is
viewing. On disconnect it deactivates so the head unit releases the media slot back to the previous
app (YouTube / YouTube Music). The Car App Library **template** surface (`IoniqVitalsCarAppService` /
`IoniqVitalsCarSession` / `VehicleStatusScreen`) is intentionally dormant: a `CarAppService` registered
under `category.MEDIA` claims Android Auto's single media slot and prevents the media browser from
binding, so the two cannot be active at once (the classes are retained, unused, in source).

## Features

- **Three-tile Android Auto dashboard** — comic-book front/rear light tiles plus a consolidated
  stats tile, refreshed in place via `notifyChildrenChanged` when a displayed value changes.
- **Auto-connect** — connects the OBD reader without opening the app:
  - `ObdConnectionReceiver` wakes on the Bluetooth `ACL_CONNECTED` broadcast for the saved dongle,
    so getting in the car (dongle powers on) connects the dashboard.
  - `IoniqVitalsMediaBrowserService` also auto-connects when Android Auto binds the media browser
    (gated to the AA/projection client so the phone's boot-time media probe doesn't trip it).
- **OBD over BLE** — foreground service polling the OBDLink CX over Bluetooth LE.
- **Mock adapter** — a built-in simulator (`Mock OBD Adapter (Simulator)`) for testing the UI
  without a car; it toggles light state on random sub-second intervals to exercise the tiles.
- **Phone debug view** — `Show Debug Info` dumps the live polling log, stitched payloads, and
  experimental readouts.

## Ioniq 5 PID reference

All vehicle reads are manufacturer **Mode 22** (UDS) requests routed to a specific ECU by
header. Byte indices are 0-based from the first byte **after** the positive-response echo
(e.g. after `620105`). Generic Mode 01 PIDs do **not** work — the E-GMP gateway blocks them
(see notes).

| Value | Header (ECU) | Mode/PID | Decode |
|-------|--------------|----------|--------|
| Display SOC | `7E4` (BMS) | `220105` | byte 31 / 2 |
| SOH | `7E4` (BMS) | `220105` | bytes 25–26 / 10 |
| 12V aux SOC | `7E5` (ICCU) | `22E011` | byte 20 |
| Coolant inlet temp | `7E4` (BMS) | `220101` | signed byte 22 |
| Pack current / voltage | `7E4` (BMS) | `220101` | signed16 @10 ×0.1 A, u16 @12 ×0.1 V |
| Headlights | `770` (IGMP) | `22BC09` (+`22BC10`) | low-beam = `BC09` byte 6 |
| **Brake lights** | `770` (IGMP) | `22BC06` | **byte 4: `0x00` off, `0x2D` pedal, `0x2C` regen — any non-zero = lamp on** |
| Brake pedal switch (fallback) | `7A0` (BCM) | `22B008` | byte 6 bit `0x10` (physical pedal only) |
| Vehicle speed *(verified, dormant)* | `7B3` (cluster) | `220100` | byte 29 = km/h |

### Notes on the hard-won ones

- **Brake lights — `770/22BC06` byte 4 is the verified signal.** It's the car's own commanded
  lamp state and covers *both* the physical pedal and regen/i-Pedal braking (the Ioniq 5 lights
  the lamps above ~0.13 G of regen deceleration per UN ECE R13-H). Found by scanning the IGMP
  `BCxx` / BCM `B0xx` families for the values Car Scanner reports (`0x2C`/`0x2D`); no public PID
  list documents it. `22B008` byte 6 only reflects the pedal switch and is the fallback if
  `BC06` returns no data.
- **Vehicle speed — `7B3/220100` byte 29 = km/h (cluster).** Verified, but kept **dormant** — the
  poll is removed because nothing in the dashboard currently uses speed.
- **No standard speed PID.** Mode 01 PID `010D` returns `NODATA` on E-GMP. There is no
  reliable speed-derived deceleration, so the old power-based "decel/regen" brake heuristic is a
  **fake stand-in** — computed for the debug readout only, never used to drive the brake lights.

## Setup

1. Open the project in Android Studio.
2. Pair the OBDLink CX in system Bluetooth settings (or use the **Mock OBD Adapter**).
3. Build and install (`Shift+F10`, or `gradlew :app:installDebug`).
4. Launch the app, pick the adapter, tap **Connect OBD** (grant Bluetooth permissions). Once an
   adapter has been selected, the app auto-connects on later sessions when the dongle connects or
   when Android Auto starts — no need to open the app each time.
5. Connect the phone to Android Auto and open **Ioniq Vitals** from the app list; the tile grid
   appears.

## Project structure

```
app/src/main/java/com/ioniq5/companion/
├── car/   # IoniqVitalsMediaBrowserService + MediaSessionManager (active surface);
│          # TileImageProvider (content-URI tile renderer);
│          # IoniqVitalsCarAppService / IoniqVitalsCarSession / VehicleStatusScreen (dormant template surface)
├── obd/   # ObdReaderService (BLE poll loop), Ioniq5Pids (parsers + PID constants),
│          # ObdConnectionReceiver (auto-connect on dongle connect),
│          # ObdSnapshot, ObdDataRepository (SharedFlow), ObdPreferences
└── ui/    # CarIconRenderer (Canvas → Bitmap for tile images and the dormant CarIcon)
```

## Notes

- Vehicle must be ON (ready) for Mode 22 responses.
- The phone app lists all paired Bluetooth devices so you can choose the adapter explicitly.
- DHU: `run_dhu.bat` forwards `tcp:5277` to a single chosen device and launches the Desktop
  Head Unit. It targets the device by **transport_id** (not serial) so it survives a wireless
  ADB serial that contains a space, even when that's the only device attached.

## License

Apache 2.0 — see kotlin-obd-api for OBD library licensing.
