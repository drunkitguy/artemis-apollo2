package com.voidlink.android.ui.apps

import com.voidlink.android.data.HostApp
import com.voidlink.android.data.KnownHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the small amount of derived state the Apps screen renders directly. */
class AppsUiStateTest {

    private val host = KnownHost(uuid = "uuid-1", name = "BATTLESTATION")

    @Test
    fun `the title is the host name once the host is known`() {
        assertEquals("BATTLESTATION", AppsUiState(host = host).title)
    }

    @Test
    fun `the title falls back to a neutral label before the host resolves`() {
        assertEquals("Apps", AppsUiState().title)
    }

    @Test
    fun `a library that is still loading is not reported as empty`() {
        // Reporting empty while loading would flash "No apps to show yet" on every open.
        assertFalse(AppsUiState(isLoading = true).isEmpty)
    }

    @Test
    fun `a finished fetch with no apps is empty`() {
        assertTrue(AppsUiState(isLoading = false).isEmpty)
        assertFalse(
            AppsUiState(
                isLoading = false,
                apps = listOf(HostApp(id = "1", name = "Hades II")),
            ).isEmpty,
        )
    }
}
