package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.model.PttSource

internal fun ownsPttRelease(active: PttSource?, requested: PttSource, failSafe: Boolean = false): Boolean =
    failSafe || active == requested
