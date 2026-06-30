package org.koitharu.kotatsu

import org.junit.Assert
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.MangaParserSource

class SourceTest {
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
