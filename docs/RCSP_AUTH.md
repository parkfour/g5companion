# JieLi RCSP Bluetooth Authentication (SAFER+ E1) Key Discovery & Verification

This document details the reverse engineering procedure, cryptographic analysis, and physical hardware verification of the device authentication challenge-response mechanism used by the JieLi AC701N SoC on DPVR G5 smart glasses.

---

## 1. Overview & Threat Model

The DPVR G5 exposes two concurrent Bluetooth Low Energy (BLE) GATT services:
1. **Eyevue Service (`0000aa12-0000-1000-8000-00805f9b34fb`)**: Standard application control (camera, media sync, voice streaming).
2. **JieLi RCSP Service (`0000ae00-0000-1000-8000-00805f9b34fb`)**: Low-level vendor silicon service handling chip diagnostics, memory access, and Over-The-Air (OTA) firmware updates.

While the Eyevue service accepts commands without cryptographic handshakes, the JieLi RCSP service (`ae00`) enforces mutual authentication (`OtaConstant.IS_NEED_DEVICE_AUTH = true`). Any attempt to initiate low-level operations or firmware flashes without solving the device challenge results in an immediate GATT disconnect.

---

## 2. Key Discovery Procedure

### A. Binary Extraction
Analysis of the official Android app (`com.dpai.app`) revealed that RCSP device authentication is handled by a native shared library:
* **Target File:** `lib/arm64-v8a/libjl_ota_auth.so`
* **Java Bridge:** `com.jieli.jl_bt_ota.impl.RcspAuth`

Decompilation of `RcspAuth.java` showed that while an API `setLinkKey(byte[])` existed, the application never invoked it, implying the use of fixed global keys or fallback credentials.

### B. Disassembly & Cryptographic Analysis
Disassembling `libjl_ota_auth.so` with Ghidra revealed an implementation of the Bluetooth SAFER+ **E1** keyed hash algorithm (traditionally used in Bluetooth legacy pairing authentication):

1. **Authentication Secret Key:**  
   Located in the `.data` section at Virtual Address (VA) `0xb150`:
   ```
   06 77 5f 87 91 8d d4 23 00 5d f1 d8 cf 0c 14 2b
   ```
2. **Salt Parameter:**  
   6 bytes at VA `0xb160` (passed as `E1` parameter 1, applied modulo 6):
   ```
   11 22 33 33 22 11
   ```
3. **Firmware Decryption Cipher Key:**  
   The `.ufw` container decryption routine utilizes the 16-bit XOR cipher key:
   ```
   0x75f1
   ```

---

## 3. Cryptographic Implementation (SAFER+ E1)

The challenge-response operates as follows:
1. **Device Challenge:** The glasses generate a 16-byte random challenge (received via characteristic `ae02` as a 17-byte frame: `[0x00][16-byte random]`).
2. **Response Generation:** The response is computed as:
   $$\text{Response} = \text{E1}(\text{Salt}, \text{Random}_{16}, \text{Key}_{16})$$
3. **Core Transformation:**
   * Standard SAFER+ 256-entry `EXP` and `LOG` permutation tables (satisfying $\text{EXP}[\text{LOG}[x]] = x$).
   * Pseudo-Hadamard Transform (PHT) linear mixing layers across 16 bytes.
   * Key schedule expansion transcribing the 16-byte master key into subkeys across all encryption rounds.

### Standalone C Verification Tool
The algorithm was reimplemented in C (`rcsp_auth.c`) and verified:
```sh
clang -O2 -o rcsp_auth rcsp_auth.c
./rcsp_auth 000102030405060708090a0b0c0d0e0f
# Output: e5 80 7e 88 7b de 07 63 0b 4d 0e 71 5b 3e 89 91
```

* **Validation:** Byte-exact matching against Ghidra p-code emulation of the native function `FUN_00101a6c`.

---

## 4. Hardware Validation (Live BLE Test)

On November 13, 2026, the key and challenge-response implementation were tested directly against physical DPVR G5 hardware over Bluetooth Low Energy:

1. **Connection & Setup:**
   * Connected to GATT service `0000ae00-0000-1000-8000-00805f9b34fb`.
   * Negotiated MTU to 509 bytes.
   * Characteristic `ae01` write property confirmed as `0x04` (`WRITE_TYPE_NO_RESPONSE`, opcode `0x52`).
2. **Handshake Pacing:**
   * After sending the reset/init sequence, the glasses emit the 16-byte random challenge after an ~80 ms settle window.
3. **Result:**
   * The glasses accepted the calculated response on characteristic `ae01` byte-for-byte.
   * Authentication state transitioned to `AUTHENTICATED`, unlocking internal RCSP diagnostic commands.

**Conclusion:** The extracted key `06 77 5f 87 91 8d d4 23 00 5d f1 d8 cf 0c 14 2b` is globally hardcoded across the DPVR G5 product line and validates physical ownership of the device.
