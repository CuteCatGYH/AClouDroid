package app.acloudroid.qwq.feature.photos.ui

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Number formatting for the info panel.
 *
 * Kept as pure functions (no Android types, no Locale-dependent behaviour beyond grouping) so the
 * formatting rules can be unit tested directly — these strings are the part of the feature most
 * likely to be subtly wrong, and a device is not needed to prove it.
 */
object PhotoInfoFormat {

    /**
     * Aperture as `f/1.85`.
     *
     * Two decimals, trailing zeros trimmed: real lenses are specified to two places
     * (f/1.78, f/2.20, f/22) and printing `f/1.80` looks like a mistake next to a camera spec sheet.
     */
    fun aperture(fNumber: Double?): String? {
        val v = fNumber ?: return null
        if (v <= 0) return null
        return "f/" + trimZeros(String.format(Locale.US, "%.2f", v))
    }

    /**
     * Shutter speed.
     *
     * Below one second this is conventionally written as a fraction (`1/739s`), which is what a
     * photographer reads; above it, as decimals (`2.5s`, `30s`). Rounding to a "nice" fraction
     * like 1/750 would be a lie, so the denominator is the real rounded value.
     */
    fun shutter(exposureTime: Double?): String? {
        val v = exposureTime ?: return null
        if (v <= 0) return null
        return if (v < 1.0) {
            val denominator = (1.0 / v).roundToInt()
            "1/${denominator}s"
        } else {
            trimZeros(String.format(Locale.US, "%.1f", v)) + "s"
        }
    }

    /** Focal length as `6.76 mm`, with the 35mm equivalent appended when it differs. */
    fun focalLength(focalLength: Double?, focalLength35: Int?): String? {
        val fl = focalLength
        val eq = focalLength35?.takeIf { it > 0 }
        return when {
            fl != null && fl > 0 && eq != null -> "${fmtMm(fl)} mm · ${eq} mm 等效"
            fl != null && fl > 0 -> "${fmtMm(fl)} mm"
            eq != null -> "${eq} mm 等效"
            else -> null
        }
    }

    /** ISO as `ISO 64`. */
    fun iso(iso: Int?): String? {
        val v = iso ?: return null
        if (v <= 0) return null
        return "ISO $v"
    }

    /** Exposure compensation as `+0.3 EV` / `-0.3 EV`. */
    fun exposureBias(bias: Double?): String? {
        val v = bias ?: return null
        if (abs(v) < 0.001) return "0 EV"
        val sign = if (v > 0) "+" else "-"
        return sign + trimZeros(String.format(Locale.US, "%.1f", abs(v))) + " EV"
    }

    /**
     * Coordinates as `36.067100, 120.382600`.
     *
     * Six decimals is ~11 cm of precision, which is well past what the capture carries but is the
     * conventional display form and round-trips cleanly if the user pastes it into a map app.
     */
    fun coordinates(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.6f, %.6f", latitude, longitude)

    /** Altitude as `15.5 m`. */
    fun altitude(meters: Double?): String? {
        val v = meters ?: return null
        return trimZeros(String.format(Locale.US, "%.1f", v)) + " m"
    }

    /** Compass heading as `273° 西` — a bare number is not meaningful to most readers. */
    fun direction(degrees: Double?): String? {
        val v = degrees ?: return null
        val normalized = ((v % 360) + 360) % 360
        return "${normalized.roundToInt()}° ${compassLabel(normalized)}"
    }

    /**
     * Converts Apple's EXIF date (`"2026:10:21 11:03:54"`) to `"2026年10月21日 11:03:54"`.
     *
     * The EXIF separator style (colons in the date part) is an EXIF artefact, not something to
     * show a user. Returns the input unchanged if it does not match the expected shape, so an
     * unusual value is displayed rather than silently dropped.
     */
    fun exifDateTime(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        // "YYYY:MM:DD HH:MM:SS"
        val parts = s.split(' ')
        if (parts.size < 2) return s
        val dateParts = parts[0].split(':')
        if (dateParts.size != 3) return s
        val (y, m, d) = dateParts
        val time = parts[1]
        val yi = y.toIntOrNull() ?: return s
        val mi = m.toIntOrNull() ?: return s
        val di = d.toIntOrNull() ?: return s
        if (mi !in 1..12 || di !in 1..31) return s
        return "${yi}年${mi}月${di}日 $time"
    }

    private fun fmtMm(v: Double): String = trimZeros(String.format(Locale.US, "%.2f", v))

    /** Trims `1.80` -> `1.8`, and `2.00` -> `2`. Never leaves a trailing dot. */
    private fun trimZeros(s: String): String =
        if (!s.contains('.')) s else s.trimEnd('0').trimEnd('.')

    private fun compassLabel(degrees: Double): String {
        val labels = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
        val idx = ((degrees + 22.5) / 45.0).toInt() % 8
        return labels[idx]
    }
}
