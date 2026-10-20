package com.android.inputmethod.latin;

import com.android.inputmethod.latin.utils.WordInputEventForPersonalization;

import java.util.ArrayList;

/**
 * The native interface of AOSP LatinIME's dictionary, for a user-imported
 * {@code libjni_latinime.so} (Google's gesture typing build). Nothing here
 * is implemented: the library's {@code JNI_OnLoad} registers its functions on
 * exactly this class name, and fails the whole load if any method it binds
 * is missing or declared with another signature. So every method the library
 * registers is declared, used or not, and R8 keeps them all
 * ({@code app/proguard-rules.pro}).
 *
 * The signatures are AOSP's interface (Apache-2.0), as the builds pinned in
 * {@code LatinImePins} bind it. Call them only after the library has loaded;
 * before that each throws {@link UnsatisfiedLinkError}.
 */
public final class BinaryDictionary {
    private BinaryDictionary() {
    }

    public static native long openNative(String sourceDir, long dictOffset, long dictSize,
            boolean isUpdatable);
    public static native long createOnMemoryNative(long formatVersion,
            String locale, String[] attributeKeyStringArray, String[] attributeValueStringArray);
    public static native void getHeaderInfoNative(long dict, int[] outHeaderSize,
            int[] outFormatVersion, ArrayList<int[]> outAttributeKeys,
            ArrayList<int[]> outAttributeValues);
    public static native boolean flushNative(long dict, String filePath);
    public static native boolean needsToRunGCNative(long dict, boolean mindsBlockByGC);
    public static native boolean flushWithGCNative(long dict, String filePath);
    public static native void closeNative(long dict);
    public static native int getFormatVersionNative(long dict);
    public static native int getProbabilityNative(long dict, int[] word);
    public static native int getMaxProbabilityOfExactMatchesNative(long dict, int[] word);
    public static native int getNgramProbabilityNative(long dict, int[][] prevWordCodePointArrays,
            boolean[] isBeginningOfSentenceArray, int[] word);
    public static native void getWordPropertyNative(long dict, int[] word,
            boolean isBeginningOfSentence, int[] outCodePoints, boolean[] outFlags,
            int[] outProbabilityInfo, ArrayList<int[][]> outNgramPrevWordsArray,
            ArrayList<boolean[]> outNgramPrevWordIsBeginningOfSentenceArray,
            ArrayList<int[]> outNgramTargets, ArrayList<int[]> outNgramProbabilityInfo,
            ArrayList<int[]> outShortcutTargets, ArrayList<Integer> outShortcutProbabilities);
    public static native int getNextWordNative(long dict, int token, int[] outCodePoints,
            boolean[] outIsBeginningOfSentence);
    public static native void getSuggestionsNative(long dict, long proximityInfo,
            long traverseSession, int[] xCoordinates, int[] yCoordinates, int[] times,
            int[] pointerIds, int[] inputCodePoints, int inputSize, int[] suggestOptions,
            int[][] prevWordCodePointArrays, boolean[] isBeginningOfSentenceArray,
            int prevWordCount, int[] outputSuggestionCount, int[] outputCodePoints,
            int[] outputScores, int[] outputIndices, int[] outputTypes,
            int[] outputAutoCommitFirstWordConfidence,
            float[] inOutWeightOfLangModelVsSpatialModel);
    public static native boolean addUnigramEntryNative(long dict, int[] word, int probability,
            int[] shortcutTarget, int shortcutProbability, boolean isBeginningOfSentence,
            boolean isNotAWord, boolean isPossiblyOffensive, int timestamp);
    public static native boolean removeUnigramEntryNative(long dict, int[] word);
    public static native boolean addNgramEntryNative(long dict,
            int[][] prevWordCodePointArrays, boolean[] isBeginningOfSentenceArray,
            int[] word, int probability, int timestamp);
    public static native boolean removeNgramEntryNative(long dict,
            int[][] prevWordCodePointArrays, boolean[] isBeginningOfSentenceArray, int[] word);
    public static native boolean updateEntriesForWordWithNgramContextNative(long dict,
            int[][] prevWordCodePointArrays, boolean[] isBeginningOfSentenceArray,
            int[] word, boolean isValidWord, int count, int timestamp);
    public static native int updateEntriesForInputEventsNative(long dict,
            WordInputEventForPersonalization[] inputEvents, int startIndex);
    public static native String getPropertyNative(long dict, String query);
    public static native boolean isCorruptedNative(long dict);
    public static native boolean migrateNative(long dict, String dictFilePath,
            long newFormatVersion);
}
