package dev.g5.companion

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FirmwareTest {
    @Test
    fun `no packages means up to date`() {
        assertEquals(Firmware.Result.UpToDate, Firmware.parse("""{"code":200,"msg":"ok","data":{}}"""))
        assertEquals(Firmware.Result.UpToDate, Firmware.parse("""{"code":200,"msg":"ok","data":null}"""))
    }

    @Test
    fun `parses the live server shape`() {
        // Captured 2026-11-12 from getNewOtaPackage?deviceVersion=2.0.1&code=S3DPVR
        val body = """{"code":200,"message":"ok","data":{"wifi":null,"ble":{"OtaPackage":{"id":"5","code":"S3DPVR",
            "bluetoothUrl":"https://example/x.ufw","deviceVersion":"2.0.2"},"InternationalData":[]}},"success":true}"""
        assertEquals(Firmware.Result.Available("2.0.2", ""), Firmware.parse(body))
        assertEquals(Firmware.Result.UpToDate, Firmware.parse("""{"code":200,"data":{"wifi":null,"ble":null}}"""))
    }

    @Test
    fun `picks version and notes in the phone language`() {
        val body = """{"code":200,"data":{"ble":{"otaPackage":{"deviceVersion":"1.0.14"},
            "internationalData":[{"languageCode":"zh","detail":"修复"},{"languageCode":"en","detail":"Bug fixes"}]}}}"""
        assertEquals(Firmware.Result.Available("1.0.14", "Bug fixes"), Firmware.parse(body, Locale.US))
    }
}
