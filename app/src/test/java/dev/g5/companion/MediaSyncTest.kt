package dev.g5.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSyncTest {
    @Test
    fun `parses novatek file list and drops unsafe or unknown names`() {
        val xml = """<?xml version="1.0" encoding="UTF-8" ?>
            <LIST><ALLFile>
            <File><NAME>2026_1112_092000_001.JPG</NAME><FPATH>A:\DCIM\100HUNTI\2026_1112_092000_001.JPG</FPATH><SIZE>812345</SIZE></File>
            <File><NAME>A:\DCIM\100HUNTI\clip.MP4</NAME><SIZE>99</SIZE></File>
            <File><NAME>note.opus</NAME></File>
            <File><NAME>REC0001.WAV</NAME><SIZE>160044</SIZE></File>
            <File><NAME>../evil.jpg</NAME><SIZE>1</SIZE></File>
            <File><NAME>readme.txt</NAME><SIZE>1</SIZE></File>
            </ALLFile></LIST>"""
        assertEquals(
            listOf(
                MediaSync.Remote("2026_1112_092000_001.JPG", 812345),
                MediaSync.Remote("clip.MP4", 99),
                MediaSync.Remote("note.opus", -1),
                MediaSync.Remote("REC0001.WAV", 160044),
                MediaSync.Remote("evil.jpg", 1), // path stripped to a bare name
            ),
            MediaSync.parseFileList(xml),
        )
    }
}
