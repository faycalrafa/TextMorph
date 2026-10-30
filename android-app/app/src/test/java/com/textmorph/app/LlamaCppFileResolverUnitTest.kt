package com.textmorph.app

import com.textmorph.app.llm.LlamaCppFileResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class LlamaCppFileResolverUnitTest {

    @Test
    fun testSafelyCloseDetachedFd_negativeFdDoesNotThrow() {
        // Negative FDs are invalid and must return immediately
        LlamaCppFileResolver.safelyCloseDetachedFd(-1)
        LlamaCppFileResolver.safelyCloseDetachedFd(-99)
    }

    @Test
    fun testSafelyCloseDetachedFd_closedFdDoesNotThrow() {
        // On Linux, high or non-existent FDs (e.g. 99999) do not exist in /proc/self/fd/
        // safelyCloseDetachedFd must detect that it does not exist and skip without crashing
        LlamaCppFileResolver.safelyCloseDetachedFd(99999)
    }

    @Test
    fun testInspectFileHeader_unknownExtension() {
        val type = LlamaCppFileResolver.inspectFileHeader("non_existent_model.xyz")
        assertNotNull(type)
    }

    private fun assertNotNull(obj: Any?) {
        org.junit.Assert.assertNotNull(obj)
    }
}
