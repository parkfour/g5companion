# DPVR G5 protocol notes

This is a reference for the G5's Bluetooth and Wi-Fi protocol. It was worked out from the DPVR Android app
(`com.dpai.app` 1.1.9, decompiled) and CyanBridge's Eyevue implementation, then checked against a real G5 (project `S3`,
customer `DPVR`, Bluetooth firmware 2.0.2, camera firmware 1.3.6, hardware revision 2, JieLi AC701N).

## Platform

The G5 is not a HeyCyan/QCSDK device:
- Its GATT table has `1800`, `ae00` (JieLi), `aa12` (commands) and `180f` (battery).
- The QC/HeyCyan SDK can open a link to it, but never finds its service (`de5bf728-…`), so every command times out.

## BLE (GATT service `0000aa12-…`)

| Characteristic | Use |
|---|---|
| `aa13` | Commands from the app (write with response) |
| `aa14` | Replies and events (notify) |
| `aa15` | AI-photo JPEG stream (notify) |

Notifications are enabled through CCCD `2902` on `aa14` and `aa15`. The vendor app then requests a 512-byte MTU.

### Frame format

```
AB 55  len_hi len_lo  cmd  payload…  crc        app → glasses
AC 55  len_hi len_lo  cmd  payload…  crc        glasses → app
len = payload length + 2
crc = (cmd + Σ payload) & 0xFF
```

A frame can span several notifications; `Eyevue.Decoder` reassembles them.

### Commands used

| cmd | Name | Payload sent → reply |
|---|---|---|
| 2 | Recording length limit | `[sec_hi, sec_lo]` |
| 22 | Capacity | `[0]` → u16. The vendor app ignores it; it is **not** a file count. |
| 23 | Battery | `[0]` → ASCII digits `"8","4"`, then a charging flag |
| 34 | Take photo | `[0x31]` (high quality; the only mode known to stream to `aa15`) |
| 35 / 36 | Video start / stop | `[1]` / `[0]` |
| 37 | *(reply)* Wi-Fi SSID | UTF-8 network name, e.g. `DH-wI-fe4d` |
| 52 | Audio recording | `[1]` start / `[0]` stop |
| 57 | Open Wi-Fi | `[0x31]` Wi-Fi Direct, `[0x30]` access point → reply 37 |
| 64 | Unsynced file count | `[0]` → reply 66, u16 |
| 68 | Transfer finished | `[0x30, 0]` = done, clear the new-media counter; `[0x30, 1]` = done, keep it |
| 69 | *(event)* Status | 9 flags: photo, audio, video, vol+, vol−, nod, shake, music, worn |
| 83 | *(event)* Battery | `[charging, percent]` |
| 85 | Device info | `[0]` → `[bt.x, bt.y, bt.z, isp.x, isp.y, isp.z, hw]` |
| 86 | Stop the built-in voice assistant | `[0]` |
| 89 | Set time | `[yy-2000, mm, dd, HH, MM, SS]` → echoed back |
| 100 | Customer | `[]` → 4 bytes project + 4 bytes customer (`S3`, `DPVR`) |
| 113 / 114 | Voice assistant status (get / set) | bit 0 = local offline speech off, bit 1 = AI wake word off |
| 151 / 152 / 153 | Stream start / data / end | On `aa14`: the wake word or AI button started a voice stream (used as the AI trigger). On `aa15`: an AI photo. |

The full command table is in the DPVR app's `com.dpai.app.bluetooth.protocol.Command`.

### AI photo stream (`aa15`)

`151` starts the photo. Each `152` packet carries a 4-byte chunk address, then JPEG bytes, then 3 trailing bytes. `153`
ends it. `Eyevue.PhotoAssembler` puts the JPEG back together.

## Media sync (Wi-Fi Direct + HTTP)

Glasses whose project ID starts with `T` use access-point mode at `192.168.169.1` (`app/getfilelist`,
`app/deletefile?file=`). The G5 (`S3`) uses Wi-Fi Direct:

1. BLE `57 [0x31]`. The glasses start Wi-Fi Direct and reply `37` with their name.
2. Find the peer whose name contains that SSID and connect with push-button setup.
3. The glasses serve a Novatek-style HTTP API at **`192.168.49.207`** (the address is fixed in the vendor app). The first
   request usually fails while the server starts up; retry.
   - List: `GET /?custom=1&cmd=3015` returns XML: `<LIST><ALLFile><File><NAME>…</NAME><FPATH>A:\DCIM\100HUNTI\…</FPATH><SIZE>…</SIZE><TIMECODE>…</TIMECODE><TIME>…</TIME><ATTR>…</ATTR></File>…`
   - File: `GET /DCIM/100HUNTI/<NAME>`
   - Thumbnail: `GET /DCIM/100HUNTI/<NAME>?custom=1&cmd=4002`
   - Delete: `GET /?custom=1&cmd=4003&str=A:\DCIM\100HUNTI\<NAME>`
4. BLE `68` to finish, then remove the Wi-Fi Direct group.

Files seen on the G5:
- `IMAGnnnn.JPG` (photos)
- `IMAGnnnn.MP4` (videos)
- `IMAGnnnn.WAV` (audio recordings; the vendor app also accepts `.mp3`)

The vendor app deletes each file right after downloading it. This app only deletes when the user opts in, and only
after the copy on the phone is verified.

## Firmware

```
GET https://bsapi.dpaiglasses.com/api/app/ota/getNewOtaPackage?deviceVersion=<bt version>&code=<project+customer>
```

No authentication is needed. The response is
`{code, message, data: {ble: Part|null, wifi: Part|null}}`, where
`Part = {OtaPackage: {deviceVersion, bluetoothUrl, wifiUrl, otaArray, packageName, …}, InternationalData: [{languageCode, detail}]}`.

- A part is returned only when the server has something newer than the version sent.
- Sending an older version returns the latest package. On 2026-11-12 that was a single Bluetooth-chip image,
  version 2.0.2: a 1.75 MB encrypted JieLi `.ufw`.
- Installing it uses JieLi's OTA path (BLE, or Wi-Fi via cmd 99). That is deliberately not implemented here.

## Voice

When the AI trigger arrives, the app:
1. Sends `86` to silence the built-in assistant.
2. Records 16 kHz audio from the glasses' Bluetooth headset microphone (SCO/LE Audio via `setCommunicationDevice`).

The glasses can also stream the voice over BLE (`70` with `151`/`153`), but this app doesn't use that.
