package com.wallpaperswitcher.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeThrottleTest {

    @Test
    fun boundsConcurrentDecodesAndRecoversAfterRelease() {
        assertTrue(DecodeThrottle.acquire(0))
        assertTrue(DecodeThrottle.acquire(0))
        val third = DecodeThrottle.acquire(50)
        try {
            assertFalse("a third concurrent full-screen decode must wait", third)
        } finally {
            if (third) DecodeThrottle.release()
            DecodeThrottle.release()
            DecodeThrottle.release()
        }
        assertTrue(DecodeThrottle.acquire(500))
        DecodeThrottle.release()
    }
}
