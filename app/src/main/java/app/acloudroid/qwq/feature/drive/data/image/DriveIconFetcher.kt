package app.acloudroid.qwq.feature.drive.data.image

import android.content.Context
import android.util.Log
import app.acloudroid.qwq.di.AppContainer
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.getup.icloudcore.Icloudcore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import org.json.JSONObject
import java.io.File

/**
 * Coil Fetcher that delegates iCloud Drive icon requests (`/getIcons`) to Go core.
 *
 * Go core (`Icloudcore.fetchDriveIconToPath`) handles session authentication, cross-domain
 * redirects (from `drivews` to `cvws`), and CDN header stripping natively without triggering
 * OkHttp HTTP/2 connection pooling issues.
 */
class DriveIconFetcher(
    private val context: Context,
    private val container: AppContainer,
    private val url: String
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val iconDir = context.cacheDir.resolve("drive_icons")
        if (!iconDir.exists()) {
            iconDir.mkdirs()
        }

        val fileName = "${url.hashCode()}.png"
        val targetFile = File(iconDir, fileName)

        if (!targetFile.exists() || targetFile.length() == 0L) {
            // Ensure Go core session is initialized before requesting the icon
            val sessionCheck = container.driveRepository.ensureGoSession()
            if (sessionCheck.isFailure) {
                Log.w(TAG, "Drive session not ready for icon fetch $url: ${sessionCheck.exceptionOrNull()?.message}")
                return@withContext null
            }

            val jsonResult = Icloudcore.fetchDriveIconToPath(url, targetFile.absolutePath)
            Log.d(TAG, "Icloudcore.fetchDriveIconToPath for $url -> $jsonResult")
            val json = JSONObject(jsonResult)
            if (!json.optBoolean("success", false)) {
                Log.w(TAG, "Failed to download icon for $url: ${json.optString("errorMessage")}")
                targetFile.delete()
                return@withContext null
            }
        }

        if (!targetFile.exists() || targetFile.length() == 0L) {
            Log.w(TAG, "Target file does not exist or is empty: ${targetFile.absolutePath}")
            return@withContext null
        }

        Log.d(TAG, "DriveIconFetcher.fetch succeeded for $url -> file len=${targetFile.length()}")

        return@withContext SourceResult(
            source = ImageSource(
                file = targetFile.toOkioPath(),
                diskCacheKey = "drive_icon_${url.hashCode()}"
            ),
            mimeType = "image/png",
            dataSource = DataSource.NETWORK
        )
    }

    class Factory(private val container: AppContainer) : Fetcher.Factory<Any> {
        override fun create(data: Any, options: Options, imageLoader: ImageLoader): Fetcher? {
            val urlString = when (data) {
                is String -> data
                is android.net.Uri -> data.toString()
                is okhttp3.HttpUrl -> data.toString()
                else -> null
            } ?: return null

            if (urlString.contains("getIcons")) {
                return DriveIconFetcher(options.context, container, urlString)
            }
            return null
        }
    }

    companion object {
        private const val TAG = "DriveIconFetcher"
    }
}
