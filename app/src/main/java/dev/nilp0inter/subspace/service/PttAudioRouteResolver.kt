package dev.nilp0inter.subspace.service

import android.media.AudioManager
import dev.nilp0inter.subspace.service.SubspaceLogger as Log
import dev.nilp0inter.subspace.audio.AudioRouteEndpoint
import dev.nilp0inter.subspace.audio.CaptureSource
import dev.nilp0inter.subspace.audio.PcmOutput
import dev.nilp0inter.subspace.audio.ROUTE_LOG_TAG
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.audio.ResponsePlayer
import dev.nilp0inter.subspace.audio.ScoRoute
import dev.nilp0inter.subspace.audio.TelecomCallScoRoute
import dev.nilp0inter.subspace.audio.TelecomCapturePcmOutput
import dev.nilp0inter.subspace.audio.RouteGateResult
import dev.nilp0inter.subspace.audio.resolveLocalAudioRoute
import dev.nilp0inter.subspace.audio.resolveScoAudioRoute
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.telecom.TelecomCarPttCoordinator

/**
 * Resolve the audio route for a given [mode].
 *
 * Extracted from [PttForegroundService] to reduce the god-service scope.
 * All parameters that were previously closed-over service fields are passed
 * explicitly.
 */
internal fun ResolvedAudioRoute.routeDebugString(): String =
    "endpoint=$endpoint sco=${sco.javaClass.simpleName} output=${output.javaClass.simpleName} " +
        "source=${source.sourceId}"

internal fun resolvePttAudioRoute(
    mode: InputMode,
    sco: ScoRoute,
    telecomCaptureOutput: PcmOutput,
    mediaResponsePlayer: ResponsePlayer,
    voiceCommunicationSource: CaptureSource,
    localOutput: PcmOutput,
    micSource: CaptureSource,
    pcmOutput: PcmOutput,
    awaitTelecomDisconnected: suspend () -> Unit?,
    releaseTelecomCaptureRoute: () -> Unit,
    releaseStaleWorkRoute: suspend (String) -> RouteGateResult = { reason -> RouteGateResult.Success(reason) },
    logAudioRouteSnapshot: (String) -> Unit,
): ResolvedAudioRoute {
    val route = when (mode) {
        InputMode.OnTheRoad -> ResolvedAudioRoute(
            sco = TelecomCallScoRoute(TelecomCarPttCoordinator::isCaptureRouteReady),
            output = TelecomCapturePcmOutput(
                captureOutput = telecomCaptureOutput,
                mediaResponsePlayer = mediaResponsePlayer,
                releaseCaptureRoute = { releaseTelecomCaptureRoute() },
                awaitTelecomDisconnected = awaitTelecomDisconnected,
            ),
            source = voiceCommunicationSource,
            endpoint = AudioRouteEndpoint.Car,
            routeGate = dev.nilp0inter.subspace.audio.releaseWorkRouteGate(
                name = "release-work-before-car",
                release = releaseStaleWorkRoute,
            ),
        )
        InputMode.Work -> resolveScoAudioRoute(
            scoRoute = sco,
            scoOutput = pcmOutput,
            scoSource = voiceCommunicationSource,
            endpoint = AudioRouteEndpoint.Rsm,
        )
        InputMode.OnAPinch -> {
            logAudioRouteSnapshot("route-resolve-local-before")
            resolveLocalAudioRoute(
                localOutput,
                micSource,
                routeGate = dev.nilp0inter.subspace.audio.releaseWorkRouteGate(
                    name = "release-work-before-local",
                    release = releaseStaleWorkRoute,
                ),
            )
        }
    }
    Log.d(ROUTE_LOG_TAG, "ROUTE_RESOLVE mode=$mode ${route.routeDebugString()}")
    return route
}

/**
 * Release the telecom capture route.
 *
 * Extracted from [PttForegroundService]; callers pass the needed
 * dependencies explicitly.
 */
internal fun releaseTelecomCaptureRoute(
    audioManager: AudioManager,
    logAudioRouteSnapshot: (String) -> Unit,
) {
    logAudioRouteSnapshot("telecom-release-before")
    audioManager.clearCommunicationDevice()
    audioManager.mode = AudioManager.MODE_NORMAL
    logAudioRouteSnapshot("telecom-release-after")
}
