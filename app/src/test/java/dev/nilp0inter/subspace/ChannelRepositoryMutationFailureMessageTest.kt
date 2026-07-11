package dev.nilp0inter.subspace

import dev.nilp0inter.subspace.model.ChannelCatalogueError
import dev.nilp0inter.subspace.model.ChannelRepositoryError
import dev.nilp0inter.subspace.model.ChannelRepositoryMutationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelRepositoryMutationFailureMessageTest {
    @Test
    fun `missing service returns the host unavailable message`() {
        val result: ChannelRepositoryMutationResult? = null

        assertEquals("Channel service is unavailable.", result.failureMessage())
    }

    @Test
    fun `successful mutation has no form failure message`() {
        assertNull(ChannelRepositoryMutationResult.Success.failureMessage())
    }

    @Test
    fun `failed mutation propagates its exact repository error message`() {
        val error = ChannelRepositoryError.Mutation(ChannelCatalogueError.UnknownChannelId("missing-instance"))

        assertEquals(
            error.message,
            ChannelRepositoryMutationResult.Failure(error).failureMessage(),
        )
    }
}
