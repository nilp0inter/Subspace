package dev.nilp0inter.subspace.service

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualPttAdapterTest {
    @Test
    fun togglePressesWhenReleasedAndStartSucceeds() {
        val events = mutableListOf<String>()
        val adapter = VirtualPttAdapter(
            press = { events += "press"; true },
            release = { events += "release" },
        )

        adapter.toggle()

        assertEquals(listOf("press"), events)
        assertEquals(VirtualPttState.Pressed, adapter.state)
    }

    @Test
    fun toggleReleasesWhenPressed() {
        val events = mutableListOf<String>()
        val adapter = VirtualPttAdapter(
            press = { events += "press"; true },
            release = { events += "release" },
        )

        adapter.toggle()
        adapter.toggle()

        assertEquals(listOf("press", "release"), events)
        assertEquals(VirtualPttState.Released, adapter.state)
    }

    @Test
    fun stopLikeReleaseReleasesOnlyWhenPressed() {
        val events = mutableListOf<String>()
        val adapter = VirtualPttAdapter(
            press = { events += "press"; true },
            release = { events += "release" },
        )

        adapter.releaseIfPressed()
        adapter.toggle()
        adapter.releaseIfPressed()

        assertEquals(listOf("press", "release"), events)
        assertEquals(VirtualPttState.Released, adapter.state)
    }

    @Test
    fun failedStartRemainsReleased() {
        val events = mutableListOf<String>()
        val adapter = VirtualPttAdapter(
            press = { events += "press"; false },
            release = { events += "release" },
        )

        adapter.toggle()

        assertEquals(listOf("press"), events)
        assertEquals(VirtualPttState.Released, adapter.state)
    }

    @Test
    fun forceReleaseReleasesActivePress() {
        val events = mutableListOf<String>()
        val adapter = VirtualPttAdapter(
            press = { events += "press"; true },
            release = { events += "release" },
        )

        adapter.toggle()
        adapter.forceRelease()

        assertEquals(listOf("press", "release"), events)
        assertEquals(VirtualPttState.Released, adapter.state)
    }
}
