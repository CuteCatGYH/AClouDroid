package app.acloudroid.qwq.feature.update.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {

    @Test
    fun testIsNewerVersion_newerMajorVersion() {
        assertTrue(UpdateRepository.isNewerVersion("v1.0", "0.1"))
        assertTrue(UpdateRepository.isNewerVersion("2.0.0", "1.9.9"))
    }

    @Test
    fun testIsNewerVersion_newerMinorVersion() {
        assertTrue(UpdateRepository.isNewerVersion("v0.2", "0.1"))
        assertTrue(UpdateRepository.isNewerVersion("0.10", "0.9"))
        assertTrue(UpdateRepository.isNewerVersion("v0.2.0", "0.1"))
    }

    @Test
    fun testIsNewerVersion_newerPatchVersion() {
        assertTrue(UpdateRepository.isNewerVersion("v0.1.1", "0.1"))
        assertTrue(UpdateRepository.isNewerVersion("0.1.2", "0.1.1"))
    }

    @Test
    fun testIsNewerVersion_sameVersion() {
        assertFalse(UpdateRepository.isNewerVersion("v0.1", "0.1"))
        assertFalse(UpdateRepository.isNewerVersion("0.1.0", "0.1"))
        assertFalse(UpdateRepository.isNewerVersion("v1.0.0", "1.0.0"))
    }

    @Test
    fun testIsNewerVersion_olderVersion() {
        assertFalse(UpdateRepository.isNewerVersion("v0.1", "0.2"))
        assertFalse(UpdateRepository.isNewerVersion("0.0.9", "0.1"))
    }

    @Test
    fun testIsNewerVersion_withBuildMetadataSuffix() {
        assertTrue(UpdateRepository.isNewerVersion("v0.2-debug", "0.1"))
        assertFalse(UpdateRepository.isNewerVersion("v0.1-release", "0.1-debug"))
    }

    @Test
    fun testIsNewerVersion_invalidOrEmptyStrings() {
        assertFalse(UpdateRepository.isNewerVersion("", "0.1"))
        assertFalse(UpdateRepository.isNewerVersion("v0.2", ""))
        assertFalse(UpdateRepository.isNewerVersion("abc", "0.1"))
    }
}
