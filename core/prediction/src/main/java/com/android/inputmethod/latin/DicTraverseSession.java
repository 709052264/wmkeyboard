package com.android.inputmethod.latin;

/**
 * The native search session of AOSP LatinIME, for a user-imported
 * {@code libjni_latinime.so}; see {@link BinaryDictionary} for why the
 * package, the name and every signature must be exactly these.
 */
public final class DicTraverseSession {
    private DicTraverseSession() {
    }

    public static native long setDicTraverseSessionNative(String locale, long dictSize);
    public static native void initDicTraverseSessionNative(long nativeDicTraverseSession,
            long dictionary, int[] previousWord, int previousWordLength);
    public static native void releaseDicTraverseSessionNative(long nativeDicTraverseSession);
}
