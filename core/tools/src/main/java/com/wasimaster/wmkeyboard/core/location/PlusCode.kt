package com.wasimaster.wmkeyboard.core.location

/**
 * Open Location Code ("Plus code"), the short grid reference Google Maps
 * shows under a dropped pin: `7MMGQCJ9+6P` is a 14 by 14 metre square in Dhaka.
 *
 * Written here from the published specification
 * (github.com/google/open-location-code, Apache-2.0), integer arithmetic as the
 * reference implementations now use, so a code never differs from Google's by
 * a floating-point rounding. Full codes only: a short code ("QCJ9+6P Dhaka")
 * needs a reference point to mean anything.
 */
object PlusCode {

    private const val ALPHABET = "23456789CFGHJMPQRVWX"
    private const val SEPARATOR = '+'
    private const val SEPARATOR_POSITION = 8
    private const val PADDING = '0'
    private const val PAIR_CODE_LENGTH = 10
    private const val GRID_CODE_LENGTH = 5
    private const val GRID_COLUMNS = 4
    private const val GRID_ROWS = 5
    private const val ENCODING_BASE = 20
    private const val MAX_CODE_LENGTH = 15

    /** 20^3 * 8000 / 20^4 ... the pair section's finest step is 1/8000 of a degree. */
    private const val PAIR_PRECISION = 8000L

    /** 5^5: five grid rows per step, five grid digits. */
    private const val LAT_GRID_PRECISION = 3125L

    /** 4^5: four grid columns per step, five grid digits. */
    private const val LNG_GRID_PRECISION = 1024L
    private const val LAT_INTEGER_MULTIPLIER = PAIR_PRECISION * LAT_GRID_PRECISION
    private const val LNG_INTEGER_MULTIPLIER = PAIR_PRECISION * LNG_GRID_PRECISION

    /**
     * The code for [latitude], [longitude] with [length] digits. 10 is a
     * 14 metre square, 8 about 275 metres, 6 about 5.5 km, 4 about 110 km;
     * 11 and up add a finer grid. Odd lengths under 10 are not valid codes and
     * are rounded down.
     */
    fun encode(latitude: Double, longitude: Double, length: Int = PAIR_CODE_LENGTH): String {
        var codeLength = length.coerceIn(2, MAX_CODE_LENGTH)
        if (codeLength < PAIR_CODE_LENGTH && codeLength % 2 == 1) codeLength--

        var latVal = Math.round((latitude.coerceIn(-90.0, 90.0) + 90.0) * LAT_INTEGER_MULTIPLIER * 1e6) / 1_000_000L
        var lngVal = Math.round((longitude + 180.0) * LNG_INTEGER_MULTIPLIER * 1e6) / 1_000_000L
        // 90° exactly is the top edge of the last row, which has no cell above it.
        val latMax = 180L * LAT_INTEGER_MULTIPLIER
        if (latVal >= latMax) latVal = latMax - 1
        val lngSpan = 360L * LNG_INTEGER_MULTIPLIER
        lngVal = ((lngVal % lngSpan) + lngSpan) % lngSpan

        val reversed = StringBuilder()
        if (codeLength > PAIR_CODE_LENGTH) {
            repeat(GRID_CODE_LENGTH) {
                val latDigit = (latVal % GRID_ROWS).toInt()
                val lngDigit = (lngVal % GRID_COLUMNS).toInt()
                reversed.append(ALPHABET[latDigit * GRID_COLUMNS + lngDigit])
                latVal /= GRID_ROWS
                lngVal /= GRID_COLUMNS
            }
        } else {
            latVal /= LAT_GRID_PRECISION
            lngVal /= LNG_GRID_PRECISION
        }
        repeat(PAIR_CODE_LENGTH / 2) {
            reversed.append(ALPHABET[(lngVal % ENCODING_BASE).toInt()])
            reversed.append(ALPHABET[(latVal % ENCODING_BASE).toInt()])
            latVal /= ENCODING_BASE
            lngVal /= ENCODING_BASE
        }
        val digits = reversed.reverse().toString()
        val code = StringBuilder(digits).insert(SEPARATOR_POSITION, SEPARATOR).toString()
        return if (codeLength >= SEPARATOR_POSITION) {
            code.substring(0, codeLength + 1)
        } else {
            code.substring(0, codeLength) + PADDING.toString().repeat(SEPARATOR_POSITION - codeLength) + SEPARATOR
        }
    }

    fun encode(point: GeoPoint, length: Int = PAIR_CODE_LENGTH): String =
        encode(point.latitude, point.longitude, length)

    /** Whether [code] is a full code: one that names a place on its own. */
    fun isFull(code: String): Boolean {
        val upper = code.uppercase()
        if (!FULL.matches(upper) && !PADDED.matches(upper)) return false
        // The first two digits are 20° steps of latitude (0 to 180) and
        // longitude (0 to 360); anything past them is off the globe.
        if (ALPHABET.indexOf(upper[0]) * ENCODING_BASE >= 180) return false
        if (ALPHABET.indexOf(upper[1]) * ENCODING_BASE >= 360) return false
        return true
    }

    /** Eight digits, the separator, then nothing or two to seven more. */
    private val FULL = Regex("^[$ALPHABET]{8}\\+(?:[$ALPHABET]{2,7})?$")

    /** Two, four or six digits padded with zeros to eight, then the separator. */
    private val PADDED = Regex("^(?:[$ALPHABET]{2}0{6}|[$ALPHABET]{4}0{4}|[$ALPHABET]{6}0{2})\\+$")

    /** The centre of the area a full [code] names, or null for anything else. */
    fun decode(code: String): GeoPoint? {
        val upper = code.trim().uppercase()
        if (!isFull(upper)) return null
        val clean = upper.replace(SEPARATOR.toString(), "").trimEnd(PADDING).take(MAX_CODE_LENGTH)
        var lat = -90.0
        var lng = -180.0
        var resolution = ENCODING_BASE.toDouble()
        var latStep = resolution
        var lngStep = resolution
        var i = 0
        while (i < minOf(clean.length, PAIR_CODE_LENGTH)) {
            latStep = resolution
            lngStep = resolution
            lat += ALPHABET.indexOf(clean[i]) * resolution
            if (i + 1 < clean.length) lng += ALPHABET.indexOf(clean[i + 1]) * resolution
            resolution /= ENCODING_BASE
            i += 2
        }
        for (j in PAIR_CODE_LENGTH until clean.length) {
            latStep /= GRID_ROWS
            lngStep /= GRID_COLUMNS
            val index = ALPHABET.indexOf(clean[j])
            lat += (index / GRID_COLUMNS) * latStep
            lng += (index % GRID_COLUMNS) * lngStep
        }
        return GeoPoint(lat + latStep / 2, lng + lngStep / 2)
    }
}
