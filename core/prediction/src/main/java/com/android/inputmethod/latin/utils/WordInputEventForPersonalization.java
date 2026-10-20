package com.android.inputmethod.latin.utils;

/**
 * The parameter type of
 * {@code BinaryDictionary.updateEntriesForInputEventsNative}, which the
 * library's registration names in that method's signature. Never built:
 * this app does not feed typing history to the library. The fields are the
 * ones its native code reads by name, kept so the type is the one it expects.
 */
public final class WordInputEventForPersonalization {
    public final int[] mTargetWord;
    public final int mPrevWordsCount;
    public final int[][] mPrevWordArray;
    public final boolean[] mIsPrevWordBeginningOfSentenceArray;
    public final boolean mIsValid;
    public final int mTimestamp;

    private WordInputEventForPersonalization() {
        mTargetWord = new int[0];
        mPrevWordsCount = 0;
        mPrevWordArray = new int[0][];
        mIsPrevWordBeginningOfSentenceArray = new boolean[0];
        mIsValid = false;
        mTimestamp = 0;
    }
}
