# DPVR vendor app: analysis notes

These notes come from decompiling the official app, **`com.dpai.app` 1.1.9-gp** (versionCode 20, targetSdk 36, built
by "watchfun"). The decompiled sources aren't committed. Locally they live in `vendor/` (gitignored); see
[Local artifacts](#local-artifacts).

Class names below are as jadx printed them. Obfuscated classes in `defpackage` (`sve`, `n60`, `u50`, …) are only valid
for this build.

## Architecture

| Area | Where | Notes |
|---|---|---|
| BLE control | `com.dpai.app.bluetooth.manager.{BleCore, SendCommandViaBle, ModBleResponse}` | `aa12`/`aa13`/`aa14`/`aa15` GATT. Frames are in `bluetooth.protocol.{Datagram, FileDatagram, Command}`. |
| SPP control | `bluetooth.manager.SppCore` | Same commands over classic Bluetooth SPP. Frame `52 58 … 58 52`. |
| Media import | `defpackage.sve` (view model), `helper.WifiP2pConnector`, `helper.WifiApConnector` | Two modes, chosen by project ID (`hk8.s = project.startsWith("T")`). |
| HTTP to glasses | `defpackage.gcl` (Retrofit), base URLs in `u50` (`c0` = `http://192.168.49.207/`, `d0` = `http://192.168.169.1/`), paths in `n60` | |
| Firmware | `view.setting.{DpaiDeviceSettingActivity, DpaiOtaActivity, DpaiOtaApActivity}`, `bluetooth.ota.*`, JieLi `com.jieli.jl_bt_ota` | See below. |
| AI assistant | `services.DpaiAiService`, `com.watchfun.aichat*`, `AiWebSocketManager` | Cloud AI over `wss://ai.watchfun.cn/...`. The glasses stream voice over BLE (cmd 70, between 151 and 153). |
| Live view | `view.live.{LiveActivity, TLiveActivity, DpaiLiveActivity}` | `APP_LIVE` (cmd 103). `T` models use RTSP at `rtsp://192.168.169.1/h264`; `S` models use the Novatek `?custom=1&cmd=3001&par=1`. |
| Speech / translation | iFlytek AIKit, `libdfmlp`, TFLite/QNN, `com.watchfun.trans*`, `wss://ai.watchfun.cn/ws/v{1,2}/translation` | Offline and cloud speech recognition, translation. |
| Other | Zego Express (video calls), libVLC, ffmpeg-kit, Firebase | |

## What the app sends off the phone

From `u50` and `com.watchfun.network`:

- **Account and backend:** `https://bsapi.dpaiglasses.com/api/` (login, tourist registration, device activation,
  project config, ads, feedback, OTA lookup).
- **Request headers on every call** (`HeadersUtils`):
  - phone brand and model
  - app version
  - OS version and type
  - project
  - **MAC address**
  - **device name**
- **Logs and metrics:**
  - `http://120.24.175.217/app/logs` and `/app/metrics` (HTTP Basic auth hard-coded in `u50.Q`)
  - `/app/device/transfer/metrics` (media-import statistics)
  - `https://oss-upload.watchfun.cn/api/logs/upload`
- **AI:**
  - `https://ai.watchfun.cn/api/`
  - `https://ai.audiovisual-future.com/api/`
  - `https://ai.stwl-smart.com/api/`
  - WebSockets at `ai.watchfun.cn`
  - An app key and secret are hard-coded in `u50.W` and `u50.X`.
- **Ads:** `app/interstitial/getAdContent`.

This app replaces all of that. Its only DPVR contact is the manual firmware check, which sends just the version and
model code.

## Media import

The G5 uses the P2P path:

1. BLE `57 [0x31]`. Reply `37` carries the SSID. The app hard-codes the password `12345678` but doesn't use it in P2P mode.
2. `WifiP2pConnector` finds the peer whose name contains the SSID and connects with PBC (`wps.setup = 0`). It retries up
   to 8 times, waits up to 180 s, and requires the phone **not** to be group owner. A phone hotspot that is on blocks it.
3. List: `GET ?custom=1&cmd=3015` (XML, parsed by `com.jingyun.common.bean.FileListParser`).
4. For each file:
   - thumbnail: `DCIM/100HUNTI/<name>?custom=1&cmd=4002`
   - original: `DCIM/100HUNTI/<name>`
   - then **immediately delete it from the glasses**: `?custom=1&cmd=4003&str=A:\DCIM\100HUNTI\<name>` (`i85` and `v35`, called from `sve` at about line 1599).
5. BLE `68` to finish:
   - all files done: `[0x30, 0x00]` (clears the glasses' new-media counter)
   - none: `[0x30, 0x01]`
   - partial: `[WIFI_FILE_DOWNLOAD_FINISH_NUN, count]`

Downloads are renamed `DH-<timestamp>-<name>` in an app-private temporary folder.

## Firmware (OTA)

### Version and lookup

1. `DpaiDeviceSettingActivity` sends BLE `85`. Reply: `bt.x.y.z`, `isp.x.y.z`, `hw`.
2. It also sends BLE `100`. Reply: project + customer, which make up the code (e.g. `S3DPVR`).
3. The lookup is `GET bsapi.dpaiglasses.com/api/app/ota/getNewOtaPackage?deviceVersion=<bt>&code=<code>` (`s4e` / `x4e` / `y4e`).
   - The response only includes `ble` / `wifi` parts that are newer.
   - Each part has `OtaPackage{bluetoothUrl, wifiUrl, otaArray, deviceVersion}` and `InternationalData[]` (release
     notes).
   - Sending an older version returns the current package, which is how `firmware/S3DPVR-ble-2.0.2.ufw` was obtained.
4. `DpaiOtaActivity.startActivity(code, bleDownloadUrl, wifiDownloadUrl, version, notes)` starts the update.
5. Files are downloaded with `om5` into `FileUtils.getOtaOutputPath()`.

### Bluetooth-chip update (JieLi RCSP OTA)

This is the `.ufw` path, used by the current G5 package.

- `initOta()`:
  - builds `defpackage.ted`, which subclasses JieLi's `BluetoothOTAManager` (`bo1`)
  - takes the default `BluetoothOTAConfigure` (`tn1`)
  - sets BLE connect retries to 3
  - registers callbacks
- `initBle()`: `bluetooth.ota.ble.BleManager.connectBleDevice(currentDevice)` opens a **second** GATT session, on JieLi's
  RCSP service:
  - service **`0000ae00-…`**
  - write **`ae01`**
  - notify **`ae02`**
  - UUIDs are in `defpackage.in1`
- `startBleOta(path)` calls `getBluetoothOption().setFirmwareFilePath(path)` and then `startOTA(callback)`.
  - The callback gets `onStartOTA`, `onProgress(type, %)`, `onStopOTA` and `onError`.
  - The `type` argument separates the two upgrade phases.
- **Device authentication is required**: `OtaConstant.IS_NEED_DEVICE_AUTH = true`, implemented by
  `com.jieli.jl_bt_ota.impl.RcspAuth` plus the native `lib/arm64-v8a/libjl_ota_auth.so`.
  - **A reimplementation needs JieLi's OTA library** (`jl_bt_ota` AAR, public on JieLi's GitHub, plus the auth .so),
    or a port of RcspAuth.
- After a reboot, the chip may come back under a different address (single-backup "loader" mode). JieLi's manager
  handles the reconnection.
- Settings in `OtaConstant`:
  - protocol BLE (0), SPP (1) as an alternative
  - no HID mode
  - files go in `JieLiOTA/upgrade`

### Camera/ISP update (Wi-Fi)

Used when `wifiUrl` is set. No such package exists for the G5 right now.

1. `openWifiOTA()` sends BLE `99 [0x31]` (`WIFI_OTA_P2P`, `0x30` for AP mode). The reply `37` gives the SSID, then
   Wi-Fi Direct connects as for media import. A phone hotspot that is on blocks it.
2. `startWifiOta(path)` sends `POST http://192.168.49.207/?custom=1&cmd=5001&par=1` with the firmware file as a
   streamed body (`view.setting.a.uploadFile`; a custom `RequestBody` with progress). It shows a 2-minute progress
   animation.
3. The glasses report the result as BLE event **`150`** (`WifiOtaResultBean.isSuccess`).
4. On success, the app sends BLE **`101`** (`SEND_ISP_VERSION`) with the new version string, then drops Wi-Fi Direct.
   It disconnects first if a Bluetooth OTA is also pending.

When both URLs are present, the activity handles a two-part update (`DpaiOtaActivity.C` / `c = 2`). The order of the two parts looks like Wi-Fi first, then Bluetooth, but that is not confirmed.

### If we implement flashing later

- **Bluetooth chip:**
  - Add JieLi's `jl_bt_ota` library.
  - Open a second GATT connection on `ae00`/`ae01`/`ae02`.
  - Copy `ted`'s configuration: default options, retry 3, device auth on.
  - Feed it the `.ufw`.
- **Battery:** keep the glasses charging or well charged. The vendor app reads the battery (`getDevicePower`) before
  starting, but no minimum level has been checked.
- **Rehearsal:** test with the same version (2.0.2 → 2.0.2) before any real upgrade, if the library allows it.
- **Risk:** an interrupted RCSP OTA normally falls back to the loader and can be retried, but none of this has been
  tried on the G5.

## Local artifacts

All gitignored, kept in this working copy only:

| Path | Contents |
|---|---|
| `vendor/dpai-1.1.9/apk/` | `base.apk` (294 MB) + `split_config.xxhdpi.apk`, pulled from the phone |
| `vendor/dpai-1.1.9/extracted/` | `classes*.dex` and `lib/arm64-v8a/*.so` (includes `libjl_ota_auth.so`, `libopusbridge.so`) |
| `vendor/dpai-1.1.9/jadx/sources/` | jadx 1.5.1 decompilation (`--no-res`) |
| `vendor/tools/jadx-1.5.1/` | the decompiler (`bin/jadx`) |
| `firmware/S3DPVR-ble-2.0.2.ufw` | latest Bluetooth-chip firmware (1.75 MB, SHA-256 `005788ba…e10a25bf`) |
| `firmware/*.json` | raw OTA lookup responses (2026-11-12) |

To re-decompile:

```sh
JAVA_OPTS=-Xmx6g vendor/tools/jadx-1.5.1/bin/jadx -q --no-res -j 8 -d vendor/dpai-1.1.9/jadx vendor/dpai-1.1.9/apk/base.apk
```
