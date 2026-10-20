package com.android.inputmethod.latin.utils;

/**
 * Dictionary helpers of AOSP LatinIME, for a user-imported
 * {@code libjni_latinime.so}; see
 * {@link com.android.inputmethod.latin.BinaryDictionary} for why the package,
 * the name and every signature must be exactly these.
 */
public final class BinaryDictionaryUtils {
    private BinaryDictionaryUtils() {
    }

    public static native boolean createEmptyDictFileNative(String filePath, long dictVersion,
            String locale, String[] attributeKeyStringArray, String[] attributeValueStringArray);
    public static native float calcNormalizedScoreNative(int[] before, int[] after, int score);
    public static native int setCurrentTimeForTestNative(int currentTime);
}
