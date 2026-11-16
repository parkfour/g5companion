# DPVR G5 Firmware Architecture & Internals

This document synthesizes findings from reverse-engineering and decompiling the official DPVR G5 smart glasses firmware (`S3DPVR-ble-2.0.2.ufw`), including processor architecture, memory layout, dual-SoC interactions, and on-chip voice recognition.

---

## 1. System Architecture Overview

Decompilation of the decrypted firmware image confirms that the DPVR G5 operates on a **dual-SoC master/coprocessor architecture**:

```
                       BLE (GATT)
  G5 Companion App  <=============>  [JieLi AC701N SoC]  (Master Controller)
                                            │
                                            │ High-Speed UART (isp_uart_rx)
                                            │ + GPIO Power Rail (isp_pwr io)
                                            ▼
                                     [Novatek ISP SoC]   (Camera Coprocessor)
                                            │
                                            │ Wi-Fi Direct (192.168.49.207)
  G5 Companion App  < - - - - - - - - - - - ┘ HTTP Media Sync
```

### A. Master Controller (JieLi AC701N / BR28 Series)
* **Processor Core:** 32-bit `pi32v2` RISC core with DSP and SIMD extensions (MAC units, saturating math).
* **Responsibilities:**
  * Bluetooth subsystem: BLE GATT services (`aa12` Eyevue, `ae00` RCSP) and Classic A2DP/HFP audio routing.
  * Power Management Unit (PMU): Battery charging curves and ADC voltage monitoring (`user_handle_battery_msg`).
  * Physical button inputs (power, capture, AI button) and touch sensor handling.
  * Dynamic power gating for the camera coprocessor (`isp_pwr io hi` / `isp_pwr io lo`).
  * On-device keyword spotting (Unisound KWS engine).
* **Memory Map & Toolchain:**
  * Firmware executes in Execute-In-Place (XIP) flash space mapped to runtime base address `0x6000000` (loaded into Ghidra at `0x5ffe000` to account for the container header offset).
  * Global Pointer: `GP = 0x106f30`.
  * Decompiled using Ghidra with the `aleph-void/ghidra-jieli` processor module.

### B. Camera / Wi-Fi Coprocessor (Novatek-Style ISP)
* **Responsibilities:** Controls the optical camera sensor, MP4 video encoding, JPEG image generation, and the Wi-Fi Direct HTTP server (`192.168.49.207`).
* **Power & Pacing Constraints:**
  * To conserve battery, the ISP is powered off by default.
  * When a camera action or Wi-Fi sync is requested, the JieLi chip raises the GPIO power rail and initializes communication over high-speed UART (`isp_uart_rx`, `isp_manager`).
  * **Timing Vulnerability:** Commands sent before the ISP reports `ISP_READY` fail (e.g. `cant take photo` error). Similarly, the HTTP server takes 1–2 seconds to boot after Wi-Fi Direct association.

---

## 2. On-Device Voice & Keyword Spotting (KWS) Subsystem

The JieLi firmware contains an integrated on-chip neural voice engine:

### A. The Engine
* **Vendor Engine:** Unisound KWS v3.2 / v5.0.0 (integrated via JieLi's `smart_voice` framework).
* **Acoustic Model (`UNISOUND_KWS_AM` @ flash offset `0x9f48c`):**
  * Factorized Time-Delay Neural Network (FTDNN) utilizing `AffineReluNormLayer` and `tdnn` layers.
  * Processes 16 kHz audio frames into phoneme posterior probabilities.
* **Grammar & Lexicon Graph (`UNISOUND_KWS_GRAMMAR` @ flash offset `0x9e2e4`):**
  * Binary Finite State Transducer (FST) graph (`v5.0.0_eng_944` and `v5.0.0_cmn_864`).
  * Statically baked into XIP flash; not a user-configurable filesystem file.

### B. Built-in Wake Words & Hidden Lexicon
Inspection of the rodata strings and the compiled FST state machine reveals pre-compiled wake words and command phrases:
* **Active Default Wake Word:** `"hey alice"` (string located at rodata offset `0x116fea`).
* **Hidden Alternative Wake Words (Baked into FST):**
  * **`"hey sunny"`**
  * **`"hey tessie"`**
  * **`"hey luma"`**
* **Baked-in Command Phrases:**
  * Media: `"play music"`, `"pause music"`, `"stop music"`, `"continue music"`, `"next song"`, `"previous song"`, `"volume up"`, `"volume down"`.
  * Camera: `"take photo"`, `"take picture"`, `"record video"`, `"record audio"`, `"stop recording"`.
  * Calls: `"answer call"`, `"hang up"`.
  * Visual queries: `"whats this"`, `"visual recognition"`.

### C. Voice Control & App Integration
* **Disabling Built-in ASR:** BLE command opcode **`113/114`** allows toggling local offline speech recognition (`bit 0`) and the AI voice trigger (`bit 1`).
* **Silencing Assistant:** BLE opcode **`86`** silences any active on-device assistant response.
* **Audio Interception:** When the physical AI button is pressed or wake word triggered, the glasses emit opcode **`151`** on characteristic `aa14` and stream raw voice audio over `aa14` (opcodes `151/152/153`), allowing G5 Companion to intercept user speech and route it to phone-side LLMs (Gemini Live, Claude, OpenAI, or local Whisper/LiteRT-LM).

---

## 3. Chip Diagnostics & OTA Authentication

The firmware exposes the vendor's low-level JieLi RCSP GATT service:
* **Service UUID:** `0000ae00-0000-1000-8000-00805f9b34fb`
* **Characteristics:** Write: `ae01`, Notify: `ae02`
* **Cryptographic Lock:** Requires Bluetooth SAFER+ **E1** challenge-response authentication.

For the full reverse-engineering procedure detailing how the authentication key was discovered from the companion app binaries and verified byte-exact on physical hardware, see:
* **[docs/RCSP_AUTH.md](RCSP_AUTH.md)** — *JieLi RCSP Bluetooth Authentication (SAFER+ E1) Key Discovery & Verification*

---

## 4. Hardware Safety & Reflashing Risk

While the `.ufw` firmware container decryption key (`0x75f1`) and RCSP authentication key are fully recovered:
* **High Brick Risk:** The DPVR G5 hardware exposes no external USB programming pins or hardware recovery test pads for the JieLi AC701N chip.
* **No Rescue Path:** If an experimental or patched firmware write fails mid-stream or corrupts the bootloader table, the glasses will permanently brick with no software recovery mechanism short of PCB micro-soldering.
* **Architecture Decision:** G5 Companion strictly implements phone-side AI routing and media management, deliberately avoiding automatic or risky firmware flashing over BLE.
