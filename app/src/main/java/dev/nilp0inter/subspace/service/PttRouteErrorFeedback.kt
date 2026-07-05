package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.ResolvedAudioRoute

internal suspend fun playRouteErrorBeepIfAcquired(route: ResolvedAudioRoute): Boolean {
    if (!route.sco.acquire()) return false
    route.output.playErrorBeep(route.sco.coldStart)
    route.output.releaseRoute()
    return true
}
