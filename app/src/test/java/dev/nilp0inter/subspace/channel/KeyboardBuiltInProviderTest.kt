package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.model.ChannelConfigurationField
import io.sleepwalker.core.keymap.HostProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardBuiltInProviderTest {
    @Test
    fun keyboardProviderExposesLinuxUsAsItsInitialHostProfileChoice() {
        val field = hostProfileField(KeyboardBuiltInProvider())

        assertEquals(
            listOf(ChannelConfigurationField.ChoiceField.Choice("linux:us", "us [linux]")),
            field.choices,
        )
    }

    @Test
    fun keyboardProviderPublishesEverySuppliedProfileAsKeySortedChoiceMetadata() {
        val provider = KeyboardBuiltInProvider(
            listOf(
                HostProfile(hostOs = "windows", layout = "us", variant = null),
                HostProfile(hostOs = "linux", layout = "de", variant = "nodeadkeys"),
                HostProfile(hostOs = "macos", layout = "fr", variant = "azerty"),
            ),
        )

        val field = hostProfileField(provider)

        assertEquals(
            listOf(
                ChannelConfigurationField.ChoiceField.Choice("linux:de:nodeadkeys", "de (nodeadkeys) [linux]"),
                ChannelConfigurationField.ChoiceField.Choice("macos:fr:azerty", "fr (azerty) [macos]"),
                ChannelConfigurationField.ChoiceField.Choice("windows:us", "us [windows]"),
            ),
            field.choices,
        )
    }

    @Test
    fun keyboardProviderReplacesItsInitialChoiceWhenHostProfilesRefresh() {
        val provider = KeyboardBuiltInProvider()

        provider.updateHostProfiles(
            listOf(
                HostProfile(hostOs = "windows", layout = "us", variant = null),
                HostProfile(hostOs = "linux", layout = "de", variant = "nodeadkeys"),
            ),
        )

        assertEquals(
            listOf(
                ChannelConfigurationField.ChoiceField.Choice("linux:de:nodeadkeys", "de (nodeadkeys) [linux]"),
                ChannelConfigurationField.ChoiceField.Choice("windows:us", "us [windows]"),
            ),
            hostProfileField(provider).choices,
        )
    }

    private fun hostProfileField(provider: KeyboardBuiltInProvider): ChannelConfigurationField.ChoiceField {
        val field = provider.descriptor.configurationFields.single()
        assertEquals("hostProfile", field.id)
        return field as? ChannelConfigurationField.ChoiceField
            ?: throw AssertionError("Expected hostProfile to be a ChoiceField, got $field")
    }
}
