package com.voidlink.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the identity and display ordering of a host's applications. */
class HostAppTest {

    private fun app(id: String, name: String, desktop: Boolean = false) =
        HostApp(id = id, name = name, isDesktop = desktop)

    @Test
    fun `desktop sorts ahead of every other title`() {
        val sorted = listOf(
            app("3", "Zork"),
            app("1", "Alpha Protocol"),
            app("0", "Desktop", desktop = true),
        ).sortedWith(HostApp.displayOrder)

        assertEquals(listOf("Desktop", "Alpha Protocol", "Zork"), sorted.map { it.name })
    }

    @Test
    fun `titles sort alphabetically ignoring case`() {
        val sorted = listOf(
            app("1", "factorio"),
            app("2", "Baldur's Gate 3"),
            app("3", "Elden Ring"),
        ).sortedWith(HostApp.displayOrder)

        assertEquals(listOf("Baldur's Gate 3", "Elden Ring", "factorio"), sorted.map { it.name })
    }

    @Test
    fun `identically named titles keep a stable order by id`() {
        // Two hosts really do publish duplicate names (two Steam entries, for instance); without a
        // tie-break the grid would reshuffle them between refreshes.
        val sorted = listOf(app("b", "Steam"), app("a", "Steam")).sortedWith(HostApp.displayOrder)

        assertEquals(listOf("a", "b"), sorted.map { it.id })
    }

    @Test
    fun `sorting is idempotent`() {
        val once = listOf(app("2", "Beta"), app("1", "Desktop", desktop = true), app("3", "alpha"))
            .sortedWith(HostApp.displayOrder)

        assertEquals(once, once.sortedWith(HostApp.displayOrder))
    }

    @Test
    fun `apps with the same fields are equal and hash alike`() {
        val a = HostApp(id = "1", name = "Hades II", supportsHdr = true)
        val b = HostApp(id = "1", name = "Hades II", supportsHdr = true)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `an app carries no image bytes, so a large library cannot retain them`() {
        // The model deliberately has no box-art field: art is fetched per visible tile instead.
        // A regression here is not cosmetic, it is an out-of-memory crash on a fifty-game host.
        val fields = HostApp::class.java.declaredFields.map { it.name }

        assertFalse("HostApp must not carry image bytes", fields.any { it.contains("boxArt") })
        assertFalse(
            "HostApp must not carry any byte array",
            HostApp::class.java.declaredFields.any { it.type == ByteArray::class.java },
        )
    }

    @Test
    fun `a different id is a different app`() {
        assertNotEquals(app("1", "Hades II"), app("2", "Hades II"))
    }

    @Test
    fun `the stub catalog offers the desktop entry`() {
        val desktop = StubAppCatalogProvider.desktopEntry

        assertTrue(desktop.isDesktop)
        assertEquals("Desktop", desktop.name)
    }
}
