package com.android.inputmethod.keyboard;

/**
 * The native key geometry of AOSP LatinIME, for a user-imported
 * {@code libjni_latinime.so}; see
 * {@link com.android.inputmethod.latin.BinaryDictionary} for why the package,
 * the name and every signature must be exactly these. The arrays are built
 * by {@code LatinImeGeometry}.
 */
public final class ProximityInfo {
    private ProximityInfo() {
    }

    public static native long setProximityInfoNative(int displayWidth, int displayHeight,
            int gridWidth, int gridHeight, int mostCommonKeyWidth, int mostCommonKeyHeight,
            int[] proximityCharsArray, int keyCount, int[] keyXCoordinates, int[] keyYCoordinates,
            int[] keyWidths, int[] keyHeights, int[] keyCharCodes, float[] sweetSpotCenterXs,
            float[] sweetSpotCenterYs, float[] sweetSpotRadii);
    public static native void releaseProximityInfoNative(long nativeProximityInfo);
}
