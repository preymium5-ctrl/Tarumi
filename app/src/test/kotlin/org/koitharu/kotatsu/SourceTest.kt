package org.koitharu.kotatsu

import org.junit.Assert
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.MangaParserSource

class SourceTest {
    @Test
    fun updatedBundleIncludesNewSourcesAndTarumiParsers() {
        val sources = MangaParserSource.entries.associateBy { it.name }
        Assert.assertEquals(1374, sources.size)
        for (name in listOf("ONISAGA_EN", "ONISAGA_FR", "ONISAGA_JA", "ONISAGA_PT_BR", "ONISAGA_PT", "ONISAGA_ES_419", "ONISAGA_ES", "CHIKARI", "RYUKOMIK", "ERISSCANS", "DIVASCANS", "MANHUARMTL", "MANHWAREAD")) {
            Assert.assertNotNull("Missing source: $name", sources[name])
        }
        Assert.assertTrue("MangaYY must remain marked as broken", sources.getValue("MANGAYY").isBroken)
    }

    @Test
    fun testSources() {
        var foundHitomi = false
        for (source in MangaParserSource.entries) {
            println("SOURCE_NAME: ${source.name} | TITLE: ${source.title} | LOCALE: ${source.locale} | TYPE: ${source.contentType}")
            if (source.name == "HITOMILA") {
                foundHitomi = true
                Assert.assertEquals("", source.locale)
                Assert.assertEquals(org.koitharu.kotatsu.parsers.model.ContentType.HENTAI, source.contentType)
                Assert.assertFalse(source.isBroken)
            }
        }
        Assert.assertTrue("HITOMILA source was not found!", foundHitomi)
    }
}
