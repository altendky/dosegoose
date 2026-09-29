package net.fstab.dosegoose.platform.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepRegistrationPolicyTest {
    @Test
    fun `initial inactive state removes any registration restored by the platform`() {
        val policy = SleepRegistrationPolicy()

        assertEquals(SleepRegistrationOperation.Remove, policy.beginNextOperation())
    }

    @Test
    fun `requirement turning on during removal registers only after removal completes`() {
        val policy = SleepRegistrationPolicy()
        assertEquals(SleepRegistrationOperation.Remove, policy.beginNextOperation())

        policy.setRequired(true)

        assertNull(policy.beginNextOperation())
        policy.complete(SleepRegistrationOperation.Remove, succeeded = true)
        assertEquals(SleepRegistrationOperation.Register, policy.beginNextOperation())
    }

    @Test
    fun `requirement turning off during registration removes only after registration completes`() {
        val policy = SleepRegistrationPolicy()
        policy.setRequired(true)
        assertEquals(SleepRegistrationOperation.Register, policy.beginNextOperation())

        policy.setRequired(false)

        assertNull(policy.beginNextOperation())
        policy.complete(SleepRegistrationOperation.Register, succeeded = true)
        assertEquals(SleepRegistrationOperation.Remove, policy.beginNextOperation())
    }

    @Test
    fun `satisfied desired state does not repeat platform work`() {
        val policy = SleepRegistrationPolicy()
        policy.setRequired(true)
        policy.beginNextOperation()
        policy.complete(SleepRegistrationOperation.Register, succeeded = true)

        assertNull(policy.beginNextOperation())
        assertFalse(policy.operationInFlight)
    }

    @Test
    fun `failed operation remains retryable on a later refresh`() {
        val policy = SleepRegistrationPolicy()
        policy.setRequired(true)
        policy.beginNextOperation()

        policy.complete(SleepRegistrationOperation.Register, succeeded = false)

        assertFalse(policy.operationInFlight)
        assertEquals(SleepRegistrationOperation.Register, policy.beginNextOperation())
        assertTrue(policy.operationInFlight)
    }
}
