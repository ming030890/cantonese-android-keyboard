package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.ui.main.OPEN_SOURCE_NOTICE_SECTIONS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrivacyAndOpenSourceNoticeTest {
    private fun projectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "app/src/main").isDirectory) cwd else cwd.parentFile
    }

    @Test
    fun openSourceNoticesLeadWithAsrModelAndAllAssetsExist() {
        val assetsDir = File(projectRoot(), "app/src/main/assets")
        assertTrue("Expected assets directory at ${assetsDir.absolutePath}", assetsDir.isDirectory)
        assertTrue(OPEN_SOURCE_NOTICE_SECTIONS.isNotEmpty())

        val first = OPEN_SOURCE_NOTICE_SECTIONS.first()
        assertTrue(first.title.contains("Qwen3-ASR-1.7B-Cantonese-HK"))
        assertTrue(first.assetPath == "licenses/Qwen3-ASR-Cantonese-HK.txt")

        for (section in OPEN_SOURCE_NOTICE_SECTIONS) {
            val file = File(assetsDir, section.assetPath)
            assertTrue("Missing open-source notice asset: ${section.assetPath}", file.isFile)
            assertTrue("Empty open-source notice asset: ${section.assetPath}", file.readText().isNotBlank())
        }

        val asrNotice = File(assetsDir, "licenses/Qwen3-ASR-Cantonese-HK.txt").readText()
        assertTrue(asrNotice.contains("joe2643/Qwen3-ASR-1.7B-Cantonese-HK"))
        assertTrue(asrNotice.contains("Qwen/Qwen3-ASR-1.7B"))
        assertTrue(asrNotice.contains("Apache License"))
    }

    @Test
    fun privacyAndOpenSourceStringsPresentAcrossAllLocales() {
        val resDir = File(projectRoot(), "app/src/main/res")
        val en = File(resDir, "values/strings.xml").readText()
        val zhHant = File(resDir, "values-b+zh+Hant/strings.xml").readText()
        val zhHans = File(resDir, "values-b+zh+Hans/strings.xml").readText()

        assertTrue(en.contains("We never send what you type to any server."))
        assertTrue(zhHant.contains("我們絕不會將你輸入嘅內容傳送到任何伺服器。"))
        assertTrue(zhHans.contains("我们绝不会将你输入的内容发送到任何服务器。"))

        for ((locale, xml) in listOf("en" to en, "zh-Hant" to zhHant, "zh-Hans" to zhHans)) {
            assertTrue("Missing ASR model in $locale open_source_card_summary", xml.contains("Qwen3-ASR-1.7B-Cantonese-HK"))
            assertTrue("Missing base ASR model in $locale open_source_asr_summary", xml.contains("Qwen/Qwen3-ASR-1.7B"))
        }
    }

    @Test
    fun productionMainManifestExcludesDebugLanTelemetryConfig() {
        val mainManifest = File(projectRoot(), "app/src/main/AndroidManifest.xml").readText()
        assertFalse(
            "Production main manifest must not include debug LAN networkSecurityConfig",
            mainManifest.contains("networkSecurityConfig"),
        )
    }
}
