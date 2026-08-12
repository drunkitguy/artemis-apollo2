package com.voidlink.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract that made an empty grid diagnosable.
 *
 * `listApps` used to return `List<HostApp>` and "return an empty list on any failure". A PC that
 * refused the request, one we could not reach, one whose reply we could not read, and one with no
 * games all produced the same value — so the screen showed the same dead end for all four, and a
 * user's report of "connects but no applications show" could not be narrowed down at all. These
 * assertions exist so that collapse cannot come back.
 */
class AppCatalogResultTest {

    @Test
    fun `a host that lists nothing is a success, not a failure`() {
        // The distinction the old contract could not express.
        val result: AppCatalogResult = AppCatalogResult.Success(emptyList())

        assertTrue(result is AppCatalogResult.Success)
        assertTrue(result.appsOrEmpty().isEmpty())
    }

    @Test
    fun `every failure carries a detail, because the generic sentence is not enough`() {
        AppCatalogFailure.entries.forEach { reason ->
            val failure = AppCatalogResult.Failure(reason, "underlying cause for $reason")

            assertNotNull(failure.detail)
            assertTrue("$reason must carry a usable detail", failure.detail.isNotBlank())
            assertTrue(failure.appsOrEmpty().isEmpty())
        }
    }

    @Test
    fun `a failure is never mistaken for an empty library`() {
        val failure: AppCatalogResult =
            AppCatalogResult.Failure(AppCatalogFailure.TRANSPORT, "SocketTimeoutException")
        val emptyLibrary: AppCatalogResult = AppCatalogResult.Success(emptyList())

        // Both have no apps...
        assertEquals(failure.appsOrEmpty(), emptyLibrary.appsOrEmpty())
        // ...but they are not the same answer, which is the entire point.
        assertTrue(failure is AppCatalogResult.Failure)
        assertTrue(emptyLibrary is AppCatalogResult.Success)
    }

    @Test
    fun `the permission failure exists as its own cause`() {
        // A paired host that will not list its apps needs the user to act on the PC, not here. It
        // must not be folded into "nothing to stream" or into a generic refusal.
        val failure = AppCatalogResult.Failure(
            AppCatalogFailure.PERMISSION_DENIED,
            "BATTLESTATION is paired with this device but has not granted it permission to list " +
                "applications",
        )

        assertEquals(AppCatalogFailure.PERMISSION_DENIED, failure.reason)
    }

    @Test
    fun `the stub provider's desktop entry survives the new contract`() {
        // The stub is what the UI is developed against; it must express a success, not a bare list,
        // or the screen gets built against a shape production never produces.
        val result: AppCatalogResult =
            AppCatalogResult.Success(listOf(StubAppCatalogProvider.desktopEntry))

        assertTrue(result is AppCatalogResult.Success)
        assertEquals(listOf(StubAppCatalogProvider.desktopEntry), result.appsOrEmpty())
    }

    @Test
    fun `an app sorts by its sort key, which need not be its name`() {
        // Apollo encodes the PC's configured order into the title with invisible characters. The
        // readable name and the ordering key are therefore different values, and the grid must
        // honour the second while showing the first.
        val sorted = listOf(
            HostApp(id = "1", name = "Alpha", sortKey = "\u200CAlpha"),
            HostApp(id = "2", name = "Zulu", sortKey = "\u200BZulu"),
        ).sortedWith(HostApp.displayOrder)

        assertEquals(listOf("Zulu", "Alpha"), sorted.map { it.name })
    }

    @Test
    fun `an app with no explicit sort key sorts by its name`() {
        // The Sunshine and GFE case, which must be untouched by the Apollo handling.
        val sorted = listOf(
            HostApp(id = "1", name = "Zulu"),
            HostApp(id = "2", name = "Alpha"),
        ).sortedWith(HostApp.displayOrder)

        assertEquals(listOf("Alpha", "Zulu"), sorted.map { it.name })
    }
}
