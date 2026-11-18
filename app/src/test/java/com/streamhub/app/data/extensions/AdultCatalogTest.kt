package com.streamhub.app.data.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.4 — the adult catalog must contain ALL adult sources that are
 * live in the API. v1.0.3 shipped 5 adult sources in the API (Pornhub,
 * XNXX, Xvideos, RedTube, YouPorn) but never registered them here, so
 * the Lock page only ever showed HentaiHaven — the user saw "sirf
 * HentaiHaven" on his phone. Every catalog entry flows into the
 * Extensions rows, the source filter chips and the browse fan-out, so
 * a missing entry here hides a working source everywhere. Eporner
 * stays OUT on purpose (its video CDN 403s the server IP, v1.0.3).
 */
class AdultCatalogTest {

    @Test
    fun `adult catalog has all six live adult sources`() {
        assertEquals(
            setOf("hentaihaven", "pornhub", "xnxx", "xvideos", "redtube", "youporn"),
            ExtensionsCatalog.adult.map { it.id }.toSet(),
        )
    }

    @Test
    fun `adult entries are all marked adult and available`() {
        ExtensionsCatalog.adult.forEach { info ->
            assertTrue(info.id, info.isAdult)
            assertTrue(info.id, info.available)
        }
    }

    @Test
    fun `eporner is not in the catalog`() {
        assertEquals(null, ExtensionsCatalog.byId("eporner"))
    }

    @Test
    fun `adult ids never leak into the verified default set`() {
        val adultIds = ExtensionsCatalog.adult.map { it.id }.toSet()
        assertTrue(ExtensionsCatalog.verifiedIds.intersect(adultIds).isEmpty())
    }
}
