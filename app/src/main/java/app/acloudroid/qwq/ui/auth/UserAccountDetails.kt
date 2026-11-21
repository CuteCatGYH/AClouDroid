package app.acloudroid.qwq.ui.auth

import org.json.JSONObject

/**
 * Parses raw JSON returned by Go core's [com.getup.icloudcore.Icloudcore.getAccountInfoJSON]
 * (from Apple's `/setup/ws/1/validate` response) to extract user account details for UI display.
 */
data class UserAccountDetails(
    val dsid: String = "",
    val fullName: String = "",
    val appleId: String = "",
    val photoUrl: String = ""
) {
    /**
     * Human-friendly name to display, falling back from [fullName] -> [appleId] -> [dsid].
     */
    fun getDisplayName(): String {
        return fullName.trim().ifBlank {
            appleId.trim().ifBlank {
                dsid.trim()
            }
        }
    }

    /**
     * Generates an Apple-style 1-2 character initials or CJK character for avatar badges.
     *
     * Examples:
     * - "张三" -> "张"
     * - "San Zhang" -> "SZ"
     * - "John" -> "J"
     * - "john.doe@icloud.com" -> "J"
     */
    fun getInitials(): String {
        val rawName = getDisplayName().trim()
        if (rawName.isBlank()) return "?"

        // If it's an email address, extract the username part before '@'
        val nameToProcess = if (rawName.contains("@")) {
            rawName.substringBefore("@").trim()
        } else {
            rawName
        }

        if (nameToProcess.isBlank()) return "?"

        val firstChar = nameToProcess.first()

        // CJK Unified Ideographs range
        if (firstChar.code in 0x4E00..0x9FA5 || firstChar.code in 0x3400..0x4DBF) {
            return firstChar.toString()
        }

        // Split by spaces or dots for Latin/English names
        val parts = nameToProcess.split(" ", ".", "_", "-").filter { it.isNotBlank() }
        if (parts.size >= 2) {
            val first = parts[0].first().uppercaseChar()
            val second = parts[1].first().uppercaseChar()
            if (first.isLetterOrDigit() && second.isLetterOrDigit()) {
                return "$first$second"
            }
        }

        return firstChar.uppercaseChar().toString()
    }

    companion object {
        val EMPTY = UserAccountDetails()

        /**
         * Safely parses `AccountInfoJSON` string from Go core.
         */
        fun fromAccountInfoJson(jsonStr: String?, storedAvatarUrl: String? = null): UserAccountDetails {
            if (jsonStr.isNullOrBlank() || jsonStr == "{}") {
                return if (!storedAvatarUrl.isNullOrBlank()) UserAccountDetails(photoUrl = storedAvatarUrl) else EMPTY
            }
            return try {
                val root = JSONObject(jsonStr)
                val dsInfo = root.optJSONObject("dsInfo") ?: JSONObject()

                val dsid = dsInfo.optString("dsid", "").ifBlank {
                    root.optString("dsid", "")
                }
                val fullName = dsInfo.optString("fullName", "").ifBlank {
                    root.optString("fullName", "").ifBlank {
                        val first = dsInfo.optString("firstName", "").ifBlank { root.optString("firstName", "") }
                        val last = dsInfo.optString("lastName", "").ifBlank { root.optString("lastName", "") }
                        "$first $last".trim()
                    }
                }
                val appleId = dsInfo.optString("appleId", "").ifBlank {
                    dsInfo.optString("appleIdEmail", "").ifBlank {
                        dsInfo.optString("primaryEmail", "").ifBlank {
                            root.optString("appleId", "").ifBlank {
                                root.optString("appleIdEmail", "").ifBlank {
                                    root.optString("primaryEmail", "")
                                }
                            }
                        }
                    }
                }

                // Check for potential photo/avatar url in dsInfo or meCard or root or storedAvatarUrl
                var photoUrl = dsInfo.optString("photoUrl", "").ifBlank { root.optString("photoUrl", "") }
                if (photoUrl.isBlank()) {
                    val meCard = dsInfo.optJSONObject("meCard") ?: root.optJSONObject("meCard")
                    if (meCard != null) {
                        photoUrl = meCard.optString("photoUrl", "").ifBlank {
                            meCard.optJSONObject("photo")?.optString("url", "") ?: ""
                        }
                    }
                }
                if (photoUrl.isBlank() && !storedAvatarUrl.isNullOrBlank()) {
                    photoUrl = storedAvatarUrl
                }

                UserAccountDetails(
                    dsid = dsid,
                    fullName = fullName,
                    appleId = appleId,
                    photoUrl = photoUrl
                )
            } catch (_: Exception) {
                if (!storedAvatarUrl.isNullOrBlank()) UserAccountDetails(photoUrl = storedAvatarUrl) else EMPTY
            }
        }
    }
}
