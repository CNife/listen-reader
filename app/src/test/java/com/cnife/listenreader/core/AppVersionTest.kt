package com.cnife.listenreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test
    fun `tag with v prefix and plain version name parse to the same version`() {
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("v1.2.3"))
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("1.2.3"))
    }

    @Test
    fun `version code matches the release pipeline mapping`() {
        assertEquals(1_002_003, AppVersion.parse("1.2.3")!!.code)
        assertEquals(1_000_000, AppVersion.parse("1.0.0")!!.code)
    }

    @Test
    fun `ordering follows numeric segments`() {
        assertTrue(AppVersion.parse("0.2.0")!! > AppVersion.parse("0.1.9")!!)
        assertTrue(AppVersion.parse("1.0.0")!! > AppVersion.parse("0.99.999")!!)
        assertEquals(AppVersion.parse("0.1.0"), AppVersion.parse("0.1.0"))
    }

    @Test
    fun `malformed versions are rejected`() {
        assertNull(AppVersion.parse(null))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("v1.2"))
        assertNull(AppVersion.parse("v1.02.3"))
        assertNull(AppVersion.parse("v1.0.1000"))
        assertNull(AppVersion.parse("v1.1000.0"))
        assertNull(AppVersion.parse("v0.0.0"))
        assertNull(AppVersion.parse("release-1.2.3"))
    }
}
