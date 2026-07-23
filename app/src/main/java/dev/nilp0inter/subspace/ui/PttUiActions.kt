package dev.nilp0inter.subspace.ui

import dev.nilp0inter.subspace.model.ChannelImplementationId
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.OpaqueJsonObject

/** Host-owned UI intents. Provider configuration is always addressed by stable IDs and opaque data. */
interface PttUiActions {
    fun requestPermissions()
    fun requestManageExternalStorage()
    fun pickDirectory(configurationOwnerId: String, fieldId: String)
    /**
     * 2.7: Launch the generic SAF directory-tree picker for one declared mount,
     * keyed by configuration owner instance + provider implementation + mount
     * declaration ID. Distinct from [pickDirectory], which addresses a scalar
     * [dev.nilp0inter.subspace.model.ChannelConfigurationField.DirectoryField].
     */
    fun pickMount(request: MountSelectionRequest)
    fun openBluetoothSettings()
    fun scanForDevice()
    fun pairTarget()
    fun refreshCarHfpConfiguration()
    fun selectCarHfpCandidate(selectionId: String)
    fun connectSerial()
    fun retry()
    fun disconnectSerial()
    fun setActiveChannel(id: String)
    fun setInputMode(mode: InputMode)
    fun navigateToRsmSetup()
    fun navigateToCarSetup()
    fun navigateToChannelConfiguration(channelId: String)
    fun navigateToChannelCreation(implementationId: ChannelImplementationId, displayName: String)
    fun navigateBack()
    fun navigateToLogAnalysis()
    fun navigateToOpenAiProfiles()
    fun phonePttPressed(channelId: String)
    fun phonePttReleased(channelId: String)
    fun createChannel(
        implementationId: ChannelImplementationId,
        displayName: String,
        payload: OpaqueJsonObject,
    ): String?
    fun updateChannelConfiguration(channelId: String, payload: OpaqueJsonObject): String?
    fun removeChannel(id: String)
    fun moveChannel(id: String, toIndex: Int)
    fun renameChannel(id: String, newName: String)
    fun createProfile(request: OpenAiProfileEditRequest): OpenAiProfileUiMutationResult
    fun updateProfile(request: OpenAiProfileEditRequest): OpenAiProfileUiMutationResult
    fun deleteProfile(id: String): OpenAiProfileUiMutationResult
    fun testProfile(id: String)
    fun refreshProfile(id: String)
    fun navigateToPackageManagement()
    fun resolvePackageRepository(url: String)
    fun selectPackageRelease(releaseId: String)
    fun confirmPackageInstall(acknowledged: Boolean)
    fun rollbackPackage(repositoryId: dev.nilp0inter.subspace.dependency.GitHubRepositoryIdentity)
    fun removePackage(repositoryId: dev.nilp0inter.subspace.dependency.GitHubRepositoryIdentity)
    fun cancelPackageInspection()
    fun refreshPackageManagement(url: String)
}
