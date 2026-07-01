package dev.nilp0inter.subspace.ui

import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.WebhookHeader
import dev.nilp0inter.subspace.model.WebhookVerb

interface PttUiActions {
    fun requestPermissions()
    fun requestManageExternalStorage()
    fun pickJournalDirectory()
    fun openBluetoothSettings()
    fun scanForDevice()
    fun pairTarget()
    fun connectSerial()
    fun retry()
    fun disconnectSerial()
    fun setJournalSaveVoice(enabled: Boolean)
    fun setJournalSaveText(enabled: Boolean)
    fun setActiveChannel(id: String)
    fun setInputMode(mode: InputMode)
    fun setDebugChannelMode(mode: dev.nilp0inter.subspace.model.DebugMode)
    fun setWebhookUrl(url: String)
    fun setWebhookVerb(verb: WebhookVerb)
    fun setWebhookHeaders(headers: List<WebhookHeader>)
    fun setWebhookBodyTemplate(bodyTemplate: String)
    fun navigateToJournalConfig()
    fun navigateToDebugConfig()
    fun navigateToWebhookConfig()
    fun navigateBack()
    fun setTtsText(text: String)
    fun setTtsVoiceStyle(style: String)
    fun setTtsLang(lang: String)
    fun setTtsTotalSteps(steps: Int)
    fun setTtsSpeed(speed: Float)
    fun requestTtsSynthesis()
    fun setSttTtsVoiceStyle(style: String)
    fun setSttTtsLang(lang: String)
    fun setSttTtsTotalSteps(steps: Int)
    fun setSttTtsSpeed(speed: Float)
    fun phonePttPressed(channelId: String)
    fun phonePttReleased(channelId: String)
}
