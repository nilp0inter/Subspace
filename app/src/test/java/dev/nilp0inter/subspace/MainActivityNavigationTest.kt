package dev.nilp0inter.subspace

import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityNavigationTest {
    @Test
    fun `leaving package management cleans up exactly once before switching to main`() {
        val events = mutableListOf<String>()

        exitDashboardRoute(
            isPackageManagement = true,
            cleanup = { events += "cleanup" },
            setMainRoute = { events += "main" },
        )

        assertEquals(listOf("cleanup", "main"), events)
    }

    @Test
    fun `ordinary back navigation does not clean up package management`() {
        val events = mutableListOf<String>()

        exitDashboardRoute(
            isPackageManagement = false,
            cleanup = { events += "cleanup" },
            setMainRoute = { events += "main" },
        )

        assertEquals(listOf("main"), events)
    }
}
