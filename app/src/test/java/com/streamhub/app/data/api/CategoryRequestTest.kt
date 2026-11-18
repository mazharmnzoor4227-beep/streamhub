package com.streamhub.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.0.3 — category → API request mapping. Every mapping is verified live
 * against /api/latest (2026-11-18): each category returns items that are
 * ACTUALLY that category. Donghua maps to ?category=donghua (40 items p1,
 * all China — Link Click, To Be Hero X; verified live after the API track
 * fixed it 2026-11-18).
 */
class CategoryRequestTest {

    @Test
    fun `anime uses type anime`() {
        assertEquals(CategoryRequest(type = "anime"), categoryRequest("anime"))
    }

    @Test
    fun `movies uses type movie`() {
        assertEquals(CategoryRequest(type = "movie"), categoryRequest("movies"))
    }

    @Test
    fun `series uses type series`() {
        assertEquals(CategoryRequest(type = "series"), categoryRequest("series"))
    }

    @Test
    fun `hollywood bollywood kdrama cdrama use category param`() {
        assertEquals(CategoryRequest(category = "hollywood"), categoryRequest("hollywood"))
        assertEquals(CategoryRequest(category = "bollywood"), categoryRequest("bollywood"))
        assertEquals(CategoryRequest(category = "kdrama"), categoryRequest("kdrama"))
        assertEquals(CategoryRequest(category = "cdrama"), categoryRequest("cdrama"))
    }

    @Test
    fun `hindi uses lang hindi`() {
        assertEquals(CategoryRequest(lang = "hindi"), categoryRequest("hindi"))
    }

    @Test
    fun `donghua uses category param`() {
        assertEquals(CategoryRequest(category = "donghua"), categoryRequest("donghua"))
    }

    @Test
    fun `unknown category has no mapping`() {
        assertNull(categoryRequest("pakistani"))
        assertNull(categoryRequest(""))
    }
}
