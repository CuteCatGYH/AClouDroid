package app.acloudroid.qwq.util

import android.content.Context
import app.acloudroid.qwq.R

object AppErrorFormatter {

    /**
     * Converts technical error messages from Go or network responses into user-friendly localized messages.
     *
     * Specifically handles VPN/proxy timeouts for China Mainland endpoints (`icloud.com.cn`).
     */
    fun formatError(rawMessage: String?, context: Context, isChinaMainland: Boolean = true): String {
        if (rawMessage.isNullOrBlank()) {
            return context.getString(R.string.drive_err_dl_generic)
        }

        if (rawMessage.contains("context deadline exceeded", ignoreCase = true) ||
            rawMessage.contains("Client.Timeout", ignoreCase = true) ||
            rawMessage.contains("timeout", ignoreCase = true) ||
            rawMessage.contains("connection refused", ignoreCase = true) ||
            rawMessage.contains("dial tcp", ignoreCase = true) ||
            rawMessage.contains("no such host", ignoreCase = true) ||
            rawMessage.contains("network is unreachable", ignoreCase = true)
        ) {
            return if (isChinaMainland) {
                context.getString(R.string.err_msg_network_timeout_cn)
            } else {
                context.getString(R.string.err_msg_network_timeout)
            }
        }

        if (rawMessage.contains("AUTH_EXPIRED", ignoreCase = true) ||
            rawMessage.contains("401") ||
            rawMessage.contains("421")
        ) {
            return context.getString(R.string.auth_err_expired)
        }

        if (rawMessage.contains("NOT_FOUND", ignoreCase = true) || rawMessage.contains("404")) {
            return context.getString(R.string.drive_err_dl_not_found)
        }

        if (rawMessage.contains("503") || rawMessage.contains("Bad Gateway", ignoreCase = true)) {
            return context.getString(R.string.drive_err_dl_service_unavailable)
        }

        if (rawMessage.contains("no data_token.url", ignoreCase = true)) {
            return context.getString(R.string.drive_err_dl_iwork)
        }

        return rawMessage
    }
}
