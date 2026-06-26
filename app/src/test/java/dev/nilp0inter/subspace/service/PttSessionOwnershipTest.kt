package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.model.PttSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PttSessionOwnershipTest {
    @Test
    fun telecomReleaseDoesNotOwnRsmSession() {
        assertFalse(ownsPttRelease(PttSource.Rsm, PttSource.CarTelecom))
    }

    @Test
    fun telecomReleaseDoesNotOwnPhoneSession() {
        assertFalse(ownsPttRelease(PttSource.Phone, PttSource.CarTelecom))
    }

    @Test
    fun telecomReleaseOwnsTelecomSession() {
        assertTrue(ownsPttRelease(PttSource.CarTelecom, PttSource.CarTelecom))
    }

    @Test
    fun failSafeReleaseCanCloseAnySession() {
        assertTrue(ownsPttRelease(PttSource.Rsm, PttSource.CarTelecom, failSafe = true))
    }
}
