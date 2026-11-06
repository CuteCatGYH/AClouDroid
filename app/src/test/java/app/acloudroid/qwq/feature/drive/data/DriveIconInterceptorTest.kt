package app.acloudroid.qwq.feature.drive.data

import app.acloudroid.qwq.feature.photos.data.image.ICloudPhotoInterceptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveIconInterceptorTest {

    @Test
    fun `isICloudApiUrl identifies drivews getIcons endpoints`() {
        val cnIconUrl = "https://p203-drivews.icloud.com.cn/getIcons?id=iCloud.com.chii.idays.storage&field=icon120x120.png"
        val globalIconUrl = "https://p68-drivews.icloud.com/getIcons?id=com.apple.Keynote&field=icon120x120.png"

        assertTrue("China drivews getIcons URL must be recognized", ICloudPhotoInterceptor.isICloudApiUrl(cnIconUrl))
        assertTrue("Global drivews getIcons URL must be recognized", ICloudPhotoInterceptor.isICloudApiUrl(globalIconUrl))
    }

    @Test
    fun `isICloudApiUrl rejects CDN content URLs`() {
        val cdnGlobalUrl = "https://cvws.icloud-content.com/original.heic"
        val cdnCnUrl = "https://cvws.icloud-content.com.cn/compl.mov"

        assertFalse("Global CDN URL must not receive Origin/Referer headers", ICloudPhotoInterceptor.isICloudApiUrl(cdnGlobalUrl))
        assertFalse("China CDN URL must not receive Origin/Referer headers", ICloudPhotoInterceptor.isICloudApiUrl(cdnCnUrl))
    }

    @Test
    fun `isICloudApiUrl rejects third party and non-http URLs`() {
        val externalUrl = "https://example.com/icon.png"
        val localFileUrl = "file:///sdcard/icon.png"

        assertFalse("External URL must be rejected", ICloudPhotoInterceptor.isICloudApiUrl(externalUrl))
        assertFalse("Local file URL must be rejected", ICloudPhotoInterceptor.isICloudApiUrl(localFileUrl))
    }
}
