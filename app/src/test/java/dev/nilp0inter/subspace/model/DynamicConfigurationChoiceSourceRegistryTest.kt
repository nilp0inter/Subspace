package dev.nilp0inter.subspace.model

import dev.nilp0inter.subspace.dependency.PackageConfigurationLimits
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DynamicConfigurationChoiceSourceRegistryTest {

    private val keyboardSource = DynamicConfigurationChoiceSourceId.KEYBOARD_OUTPUT_PROFILES
    private val probeSource = DynamicConfigurationChoiceSourceId("probe-source")

    private fun registryWith(
        source: DynamicConfigurationChoiceSourceId = probeSource,
        deadline: Duration = 5.seconds,
        resolver: DynamicConfigurationChoiceResolver,
    ): DynamicConfigurationChoiceSourceRegistry =
        DynamicConfigurationChoiceSourceRegistry().apply { register(source, deadline, resolver) }

    @Test
    fun `registered public source publishes bounded scalar choices unchanged`() = runTest {
        val registry = registryWith(keyboardSource) { _ ->
            DynamicConfigurationChoiceResolution.Available(
                listOf(
                    DynamicConfigurationChoice("linux:us", "us [linux]"),
                    DynamicConfigurationChoice("windows:us", "us [windows]"),
                ),
            )
        }

        assertEquals(
            DynamicConfigurationChoiceResolution.Available(
                listOf(
                    DynamicConfigurationChoice("linux:us", "us [linux]"),
                    DynamicConfigurationChoice("windows:us", "us [windows]"),
                ),
            ),
            registry.resolve(DynamicConfigurationChoiceRequest(keyboardSource)),
        )
    }

    @Test
    fun `empty source publishes an empty available choice list`() = runTest {
        val registry = registryWith { _ -> DynamicConfigurationChoiceResolution.Available(emptyList()) }

        assertEquals(
            DynamicConfigurationChoiceResolution.Available(emptyList()),
            registry.resolve(DynamicConfigurationChoiceRequest(probeSource)),
        )
    }

    @Test
    fun `missing source resolves unavailable without invoking any registered resolver`() = runTest {
        var invoked = false
        val registry = registryWith { _ ->
            invoked = true
            DynamicConfigurationChoiceResolution.Available(emptyList())
        }

        assertEquals(
            DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE,
            ),
            registry.resolve(DynamicConfigurationChoiceRequest(DynamicConfigurationChoiceSourceId("unregistered-source"))),
        )
        assertFalse(invoked)
    }

    @Test
    fun `failing source resolves discovery failed without partial publication`() = runTest {
        val registry = registryWith { _ -> error("source backend failure") }

        assertEquals(
            DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.DISCOVERY_FAILED,
            ),
            registry.resolve(DynamicConfigurationChoiceRequest(probeSource)),
        )
    }

    @Test
    fun `source exceeding its deadline resolves timed out without publication`() = runTest {
        val registry = registryWith(deadline = 50.milliseconds) { _ ->
            delay(10_000) // virtual time; the 50ms source deadline fires first
            DynamicConfigurationChoiceResolution.Available(
                listOf(DynamicConfigurationChoice("linux:us", "us [linux]")),
            )
        }

        assertEquals(
            DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.RESOLUTION_TIMED_OUT,
            ),
            registry.resolve(DynamicConfigurationChoiceRequest(probeSource)),
        )
    }

    @Test
    fun `duplicate choice ids are rejected all or nothing`() = runTest {
        val registry = registryWith { _ ->
            DynamicConfigurationChoiceResolution.Available(
                listOf(
                    DynamicConfigurationChoice("linux:us", "us [linux]"),
                    DynamicConfigurationChoice("linux:us", "us [linux] copy"),
                ),
            )
        }

        assertEquals(
            DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE,
            ),
            registry.resolve(DynamicConfigurationChoiceRequest(probeSource)),
        )
    }

    @Test
    fun `duplicate choice labels are rejected all or nothing`() = runTest {
        val registry = registryWith { _ ->
            DynamicConfigurationChoiceResolution.Available(
                listOf(
                    DynamicConfigurationChoice("linux:us", "us"),
                    DynamicConfigurationChoice("linux:de", "us"),
                ),
            )
        }

        assertEquals(
            DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE,
            ),
            registry.resolve(DynamicConfigurationChoiceRequest(probeSource)),
        )
    }

    @Test
    fun `publications at the exact choice count bound pass and one past fails`() = runTest {
        val atBound = (1..PackageConfigurationLimits.MAX_CHOICES)
            .map { DynamicConfigurationChoice("id-$it", "label-$it") }
        val passing = registryWith { _ -> DynamicConfigurationChoiceResolution.Available(atBound) }
        assertEquals(
            DynamicConfigurationChoiceResolution.Available(atBound),
            passing.resolve(DynamicConfigurationChoiceRequest(probeSource)),
        )

        val overBound = atBound + DynamicConfigurationChoice("id-extra", "label-extra")
        val failing = registryWith { _ -> DynamicConfigurationChoiceResolution.Available(overBound) }
        assertEquals(
            DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE,
            ),
            failing.resolve(DynamicConfigurationChoiceRequest(probeSource)),
        )
    }

    @Test
    fun `labels at the exact byte bound pass and one past fails`() = runTest {
        val atBound = "a".repeat(PackageConfigurationLimits.MAX_LABEL_BYTES)
        val passing = registryWith { _ ->
            DynamicConfigurationChoiceResolution.Available(listOf(DynamicConfigurationChoice("linux:us", atBound)))
        }
        assertEquals(
            DynamicConfigurationChoiceResolution.Available(listOf(DynamicConfigurationChoice("linux:us", atBound))),
            passing.resolve(DynamicConfigurationChoiceRequest(probeSource)),
        )

        val overBound = "a".repeat(PackageConfigurationLimits.MAX_LABEL_BYTES + 1)
        val failing = registryWith { _ ->
            DynamicConfigurationChoiceResolution.Available(listOf(DynamicConfigurationChoice("linux:us", overBound)))
        }
        assertEquals(
            DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE,
            ),
            failing.resolve(DynamicConfigurationChoiceRequest(probeSource)),
        )
    }

    @Test
    fun `choice ids at the exact byte bound pass and one past fails`() = runTest {
        val atBound = "i".repeat(PackageConfigurationLimits.MAX_STRING_VALUE_BYTES)
        val passing = registryWith { _ ->
            DynamicConfigurationChoiceResolution.Available(listOf(DynamicConfigurationChoice(atBound, "us [linux]")))
        }
        assertEquals(
            DynamicConfigurationChoiceResolution.Available(listOf(DynamicConfigurationChoice(atBound, "us [linux]"))),
            passing.resolve(DynamicConfigurationChoiceRequest(probeSource)),
        )

        val overBound = "i".repeat(PackageConfigurationLimits.MAX_STRING_VALUE_BYTES + 1)
        val failing = registryWith { _ ->
            DynamicConfigurationChoiceResolution.Available(listOf(DynamicConfigurationChoice(overBound, "us [linux]")))
        }
        assertEquals(
            DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE,
            ),
            failing.resolve(DynamicConfigurationChoiceRequest(probeSource)),
        )
    }

    @Test
    fun `unrepresentable utf-8 labels are rejected without publication`() = runTest {
        val registry = registryWith { _ ->
            DynamicConfigurationChoiceResolution.Available(
                listOf(DynamicConfigurationChoice("linux:us", "us-\uD800-linux")),
            )
        }

        assertEquals(
            DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE,
            ),
            registry.resolve(DynamicConfigurationChoiceRequest(probeSource)),
        )
    }

    @Test
    fun `dependency values reach the registered resolver unchanged`() = runTest {
        var received: DynamicConfigurationChoiceRequest? = null
        val registry = registryWith { request ->
            received = request
            DynamicConfigurationChoiceResolution.Available(emptyList())
        }

        registry.resolve(DynamicConfigurationChoiceRequest(probeSource, dependencyValue = "profile-1"))

        assertEquals(
            DynamicConfigurationChoiceRequest(probeSource, dependencyValue = "profile-1"),
            received,
        )
    }

    @Test
    fun `registration rejects non positive deadlines`() {
        val registry = DynamicConfigurationChoiceSourceRegistry()

        assertThrows(IllegalArgumentException::class.java) {
            registry.register(probeSource, Duration.ZERO) { _ ->
                DynamicConfigurationChoiceResolution.Available(emptyList())
            }
        }
    }

    @Test
    fun `registration rejects duplicate public sources without replacement`() = runTest {
        val registry = registryWith { _ ->
            DynamicConfigurationChoiceResolution.Available(listOf(DynamicConfigurationChoice("original", "Original")))
        }

        assertThrows(IllegalArgumentException::class.java) {
            registry.register(probeSource, 5.seconds) { _ ->
                DynamicConfigurationChoiceResolution.Available(listOf(DynamicConfigurationChoice("replacement", "Replacement")))
            }
        }
        assertEquals(
            DynamicConfigurationChoiceResolution.Available(listOf(DynamicConfigurationChoice("original", "Original"))),
            registry.resolve(DynamicConfigurationChoiceRequest(probeSource)),
        )
    }

    @Test
    fun `source identifiers are bounded nonblank utf-8`() {
        assertThrows(IllegalArgumentException::class.java) { DynamicConfigurationChoiceSourceId("   ") }
        assertThrows(IllegalArgumentException::class.java) {
            DynamicConfigurationChoiceSourceId("s".repeat(PackageConfigurationLimits.MAX_FIELD_ID_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) { DynamicConfigurationChoiceSourceId("bad-\uD800-id") }
        assertEquals(
            "keyboard-output-profiles",
            DynamicConfigurationChoiceSourceId.KEYBOARD_OUTPUT_PROFILES.value,
        )
    }

    @Test
    fun `reference state projects scalar membership without host objects`() {
        val available = DynamicConfigurationChoiceResolution.Available(
            listOf(DynamicConfigurationChoice("linux:us", "us [linux]")),
        )

        assertEquals(DynamicConfigurationReferenceState.AVAILABLE, available.referenceState("linux:us"))
        assertEquals(DynamicConfigurationReferenceState.UNAVAILABLE, available.referenceState("linux:de"))
        assertEquals(DynamicConfigurationReferenceState.UNAVAILABLE, available.referenceState(null))
        assertEquals(DynamicConfigurationReferenceState.UNAVAILABLE, available.referenceState("  "))
        assertEquals(
            DynamicConfigurationReferenceState.UNAVAILABLE,
            DynamicConfigurationChoiceResolution.Available(emptyList()).referenceState("linux:us"),
        )
        assertEquals(
            DynamicConfigurationReferenceState.UNAVAILABLE,
            DynamicConfigurationChoiceResolution.Loading.referenceState("linux:us"),
        )
        assertEquals(
            DynamicConfigurationReferenceState.UNAVAILABLE,
            DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE,
            ).referenceState("linux:us"),
        )
    }
}
