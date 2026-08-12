package com.voidlink.android.protocol.http

import com.voidlink.android.protocol.ProtocolConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers `/applist` as **Apollo** actually serves it, rather than as vanilla Sunshine does.
 *
 * A real handheld connected to an Apollo host, showed "Nothing to stream", and listed the same
 * host's games correctly in another client on the same device — so the host and the network were
 * both fine and the difference was in what we sent or how we read the reply. Apollo's document
 * carries two things upstream Sunshine's does not: extra `<UUID>`/`<IDX>` children, and titles
 * prefixed with invisible ordering characters. Both are pinned here.
 */
class ApolloAppListTest {

    /** U+200B — Apollo's "0" ordering bit. */
    private val zero = ProtocolConstants.APP_TITLE_ORDER_PAD_ZERO

    /** U+200C — Apollo's "1" ordering bit. */
    private val one = ProtocolConstants.APP_TITLE_ORDER_PAD_ONE

    @Test
    fun `Apollo's extra App children do not stop an entry being read`() {
        // Apollo adds <UUID> and <IDX>; a parser that required an exact child set would drop every
        // entry and produce an empty library from a perfectly good response.
        val apps = AppListEntry.listFromXml(
            root(
                apolloApp(id = "881448767", title = "Steam Big Picture", uuid = "abc-1", idx = "0"),
                apolloApp(id = "1", title = "Desktop", uuid = "abc-2", idx = "1"),
            ),
        )

        assertEquals(2, apps.size)
        assertEquals(listOf("Desktop", "Steam Big Picture"), apps.map { it.title })
    }

    @Test
    fun `invisible ordering characters are stripped from what the user sees`() {
        val apps = AppListEntry.listFromXml(
            root(apolloApp(id = "7", title = "$zero$one${zero}Factorio")),
        )

        assertEquals("Factorio", apps[0].title)
        // Nothing invisible survives into the displayed name.
        assertFalse(apps[0].title.any { it == zero || it == one })
    }

    @Test
    fun `the host's own ordering is honoured, not alphabetical order`() {
        // This is the whole point of Apollo's padding: the PC decides the order, and encodes it so
        // that a client sorting by title reproduces it. Stripping the prefix for display without
        // keeping it for sorting would silently re-alphabetise the user's carefully arranged list.
        val apps = AppListEntry.listFromXml(
            root(
                apolloApp(id = "1", title = "${one}Alpha"),
                apolloApp(id = "2", title = "${zero}Zulu"),
            ),
        )

        assertEquals(listOf("Zulu", "Alpha"), apps.map { it.title })
    }

    @Test
    fun `a title that is nothing but padding still yields a usable name`() {
        // Defensive: an empty display name would be dropped by the caller, turning a host quirk
        // into a game that silently vanishes from the grid.
        assertTrue(AppListEntry.displayTitle("$zero$one").isNotEmpty())
        // And a name with no padding at all is returned untouched.
        assertEquals("Factorio", AppListEntry.displayTitle("Factorio"))
    }

    @Test
    fun `a padded Desktop entry is still recognised as Desktop`() {
        val apps = AppListEntry.listFromXml(root(apolloApp(id = "1", title = "${zero}Desktop")))

        assertTrue(apps[0].isDesktop)
    }

    @Test
    fun `Apollo's permission-denied placeholder is recognised, not shown as a game`() {
        // Apollo answers status_code=200 with exactly one entry named "Permission Denied" when a
        // paired client may not list applications. Treating it as a library would offer the user a
        // game that cannot launch; filtering it silently would show an empty grid. Neither says the
        // one thing that matters: the fix is on the PC.
        val apps = AppListEntry.listFromXml(
            root(apolloApp(id = "114514", title = "Permission Denied")),
        )

        assertTrue(AppListEntry.isPermissionDeniedPlaceholder(apps))
        assertTrue(apps[0].isPermissionDenied)
    }

    @Test
    fun `the placeholder is matched on its id too, in case the wording changes`() {
        val apps = AppListEntry.listFromXml(root(apolloApp(id = "114514", title = "Nope")))

        assertTrue(AppListEntry.isPermissionDeniedPlaceholder(apps))
    }

    @Test
    fun `a real one-game library is not mistaken for the placeholder`() {
        val apps = AppListEntry.listFromXml(root(apolloApp(id = "42", title = "Factorio")))

        assertFalse(AppListEntry.isPermissionDeniedPlaceholder(apps))
    }

    @Test
    fun `several apps are never the placeholder, whatever they are called`() {
        val apps = AppListEntry.listFromXml(
            root(
                apolloApp(id = "114514", title = "Permission Denied"),
                apolloApp(id = "2", title = "Factorio"),
            ),
        )

        assertFalse(AppListEntry.isPermissionDeniedPlaceholder(apps))
    }

    @Test
    fun `Apollo's crc32-derived ids stay inside Int range and survive parsing`() {
        // Apollo derives ids as abs((int32) crc32(name+image)), so they are always positive and fit
        // in 31 bits — but they are large, and a parser using Int would still be at risk.
        val apps = AppListEntry.listFromXml(root(apolloApp(id = "2147483647", title = "Edge")))

        assertEquals(2147483647L, apps[0].id)
    }

    @Test
    fun `an unpadded Sunshine document is unaffected`() {
        // The same parser serves both forks; the Apollo handling must not perturb the plain case.
        val apps = AppListEntry.listFromXml(
            root(
                XmlNode(
                    "App",
                    children = listOf(
                        XmlNode("IsHdrSupported", text = "1"),
                        XmlNode("AppTitle", text = "Desktop"),
                        XmlNode("ID", text = "1"),
                    ),
                ),
            ),
        )

        assertEquals("Desktop", apps[0].title)
        assertEquals("Desktop", apps[0].sortKey)
        assertTrue(apps[0].hdrSupported)
    }

    // ---- Fixtures --------------------------------------------------------------------------

    /** An `<App>` shaped exactly as Apollo emits one. */
    private fun apolloApp(
        id: String,
        title: String,
        uuid: String = "00000000-0000-0000-0000-000000000000",
        idx: String = "0",
        hdr: String = "0",
    ) = XmlNode(
        name = "App",
        children = listOf(
            XmlNode("IsHdrSupported", text = hdr),
            XmlNode("AppTitle", text = title),
            XmlNode("UUID", text = uuid),
            XmlNode("IDX", text = idx),
            XmlNode("ID", text = id),
        ),
    )

    private fun root(vararg children: XmlNode) = XmlNode(
        name = "root",
        attributes = mapOf("status_code" to "200"),
        children = children.toList(),
    )
}
