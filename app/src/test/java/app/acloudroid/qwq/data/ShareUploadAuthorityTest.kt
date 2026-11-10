package app.acloudroid.qwq.data

import app.acloudroid.qwq.feature.photos.data.ShareUploadHandler
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which share URIs may be rewritten with `requireOriginal` before being read.
 *
 * ## The bug this exists to prevent
 *
 * `MediaStore.setRequireOriginal(uri)` is the standard way to un-redact GPS, so it was applied to
 * every `content://` share URI. It rewrites the URI to `...?requireOriginal=1`, and a third-party
 * `FileProvider` treats that as a *different, un-granted* URI. Samsung's Gallery provider rejected
 * it outright:
 *
 * ```
 * Permission Denial: reading com.samsung.android.gallery.app.provider.GalleryFileProvider
 *   uri content://com.sec.android.gallery3d.fileprovider/...?requireOriginal=1
 *   requires the provider be exported, or grantUriPermission()
 * ```
 *
 * `ShareUploadHandler.copyStreamToCache` then fell back to queuing the original URI, which the
 * background worker could not read either (the grant dies with the share Activity), so the row
 * cycled `PENDING -> fail -> PENDING` and rendered as permanently "uploading".
 *
 * The rule is therefore: only MediaProvider's own authorities get the parameter. Everything else
 * is read through the exact URI the share intent granted.
 */
class ShareUploadAuthorityTest {

    private fun isMediaStore(authority: String?) =
        ShareUploadHandler.isMediaStoreAuthority(authority)

    @Test
    fun `plain media authority is rewritten`() {
        assertTrue(isMediaStore("media"))
    }

    @Test
    fun `storage access framework documents authority is rewritten`() {
        assertTrue(isMediaStore("com.android.providers.media.documents"))
    }

    /** Vendor builds embed MediaProvider under a package-suffixed authority. */
    @Test
    fun `module suffixed media authority is rewritten`() {
        assertTrue(isMediaStore("com.google.android.providers.media.module.media"))
        assertTrue(isMediaStore("com.samsung.android.providers.media.media"))
    }

    /**
     * The exact authority that failed on device. A `FileProvider` has no idea what
     * `requireOriginal` means and refuses the rewritten form.
     */
    @Test
    fun `samsung gallery fileprovider authority is never rewritten`() {
        assertFalse(isMediaStore("com.sec.android.gallery3d.fileprovider"))
    }

    @Test
    fun `google photos content provider is never rewritten`() {
        assertFalse(isMediaStore("com.google.android.apps.photos.contentprovider"))
    }

    /** An authority merely *containing* "media" is not MediaProvider. */
    @Test
    fun `authority with media substring is not mediastore`() {
        assertFalse(isMediaStore("com.some.mediabrowser.provider"))
        assertFalse(isMediaStore("com.example.mediafile"))
    }

    @Test
    fun `blank authority is not mediastore`() {
        assertFalse(isMediaStore(null))
        assertFalse(isMediaStore(""))
        assertFalse(isMediaStore("   "))
    }
}
