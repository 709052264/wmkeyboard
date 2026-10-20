# libwmvoikko: wm_voikko_jni.cpp over a static libvoikko, VFST backend only.
# Driven by build.sh, which passes the corevoikko tree in VOIKKO_SRC; see
# README.md. WMVOIKKO_HOST=ON builds the same library for the machine running
# the build (a .dylib or .so to test the JNI from a desktop JVM) plus
# voikkovfstc, the compiler build-dict.sh turns voikko-fi into a dictionary
# with.
cmake_minimum_required(VERSION 3.18)
project(wmvoikko CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(VOIKKO_SRC "" CACHE PATH "corevoikko source tree")
option(WMVOIKKO_HOST "Build for this machine, with voikkovfstc" OFF)
if (NOT EXISTS "${VOIKKO_SRC}/libvoikko/src/voikko.h")
    message(FATAL_ERROR "VOIKKO_SRC must point at a corevoikko checkout")
endif()
set(VS ${VOIKKO_SRC}/libvoikko/src)

# Upstream's own list (libvoikko/src/Makefile.am, libvoikko_la_SOURCES plus
# VFST_CPP_FILES), with neither the HFST, lttoolbox nor VISLCG3 backends:
# the Finnish dictionary is VFST, and lttoolbox's two files are the only
# GPL-only sources in the library. Every file built here is offered under
# MPL 1.1, GPL 2+ and LGPL 2.1+ (LICENSE.CORE). The grammar checker stays:
# setup.cpp constructs one for every handle.
add_library(voikko STATIC
    ${VS}/fst/Configuration.cpp
    ${VS}/fst/WeightedConfiguration.cpp
    ${VS}/fst/Transducer.cpp
    ${VS}/fst/UnweightedTransducer.cpp
    ${VS}/fst/WeightedTransducer.cpp
    ${VS}/spellchecker/spell.cpp
    ${VS}/spellchecker/suggestions.cpp
    ${VS}/spellchecker/Speller.cpp
    ${VS}/spellchecker/SpellWithPriority.cpp
    ${VS}/spellchecker/SpellUtils.cpp
    ${VS}/spellchecker/AnalyzerToSpellerAdapter.cpp
    ${VS}/spellchecker/FinnishSpellerTweaksWrapper.cpp
    ${VS}/spellchecker/FixedResultSpeller.cpp
    ${VS}/spellchecker/SpellerFactory.cpp
    ${VS}/spellchecker/SpellerCache.cpp
    ${VS}/spellchecker/suggestion/Suggestion.cpp
    ${VS}/spellchecker/suggestion/SuggestionStatus.cpp
    ${VS}/spellchecker/suggestion/SuggestionGeneratorFactory.cpp
    ${VS}/spellchecker/suggestion/SuggestionGeneratorCaseChange.cpp
    ${VS}/spellchecker/suggestion/SuggestionGeneratorDeletion.cpp
    ${VS}/spellchecker/suggestion/SuggestionGeneratorDeleteTwo.cpp
    ${VS}/spellchecker/suggestion/SuggestionGeneratorInsertion.cpp
    ${VS}/spellchecker/suggestion/SuggestionGeneratorInsertSpecial.cpp
    ${VS}/spellchecker/suggestion/SuggestionGeneratorMultiReplacement.cpp
    ${VS}/spellchecker/suggestion/SuggestionGeneratorNull.cpp
    ${VS}/spellchecker/suggestion/SuggestionGeneratorReplacement.cpp
    ${VS}/spellchecker/suggestion/SuggestionGeneratorReplaceTwo.cpp
    ${VS}/spellchecker/suggestion/SuggestionGeneratorSoftHyphens.cpp
    ${VS}/spellchecker/suggestion/SuggestionGeneratorSplitWord.cpp
    ${VS}/spellchecker/suggestion/SuggestionGeneratorSwap.cpp
    ${VS}/spellchecker/suggestion/SuggestionGeneratorVowelChange.cpp
    ${VS}/spellchecker/suggestion/SuggestionStrategy.cpp
    ${VS}/spellchecker/suggestion/SuggestionStrategyOcr.cpp
    ${VS}/spellchecker/suggestion/SuggestionStrategyTyping.cpp
    ${VS}/utils/utils.cpp
    ${VS}/utils/StringUtils.cpp
    ${VS}/morphology/interface.cpp
    ${VS}/morphology/Analysis.cpp
    ${VS}/morphology/Analyzer.cpp
    ${VS}/morphology/AnalyzerFactory.cpp
    ${VS}/morphology/NullAnalyzer.cpp
    ${VS}/setup/setup.cpp
    ${VS}/setup/Dictionary.cpp
    ${VS}/setup/BackendProperties.cpp
    ${VS}/setup/DictionaryFactory.cpp
    ${VS}/setup/DictionaryLoader.cpp
    ${VS}/setup/DictionaryException.cpp
    ${VS}/setup/interface.cpp
    ${VS}/setup/LanguageTag.cpp
    ${VS}/hyphenator/interface.cpp
    ${VS}/hyphenator/AnalyzerToFinnishHyphenatorAdapter.cpp
    ${VS}/hyphenator/Hyphenator.cpp
    ${VS}/hyphenator/HyphenatorFactory.cpp
    ${VS}/character/charset.cpp
    ${VS}/character/SimpleChar.cpp
    ${VS}/tokenizer/interface.cpp
    ${VS}/tokenizer/Tokenizer.cpp
    ${VS}/sentence/interface.cpp
    ${VS}/sentence/Sentence.cpp
    ${VS}/grammar/interface.cpp
    ${VS}/grammar/FinnishRuleEngine/checks.cpp
    ${VS}/grammar/error.cpp
    ${VS}/grammar/Analysis.cpp
    ${VS}/grammar/Sentence.cpp
    ${VS}/grammar/Paragraph.cpp
    ${VS}/grammar/CacheEntry.cpp
    ${VS}/grammar/GcCache.cpp
    ${VS}/grammar/VoikkoGrammarError.cpp
    ${VS}/grammar/NullGrammarChecker.cpp
    ${VS}/grammar/FinnishGrammarChecker.cpp
    ${VS}/grammar/GrammarChecker.cpp
    ${VS}/grammar/GrammarCheckerFactory.cpp
    ${VS}/grammar/FinnishAnalysis.cpp
    ${VS}/grammar/HfstAnalysis.cpp
    ${VS}/grammar/FinnishRuleEngine.cpp
    ${VS}/grammar/RuleEngine.cpp
    ${VS}/grammar/FinnishRuleEngine/CapitalizationCheck.cpp
    ${VS}/grammar/FinnishRuleEngine/CompoundVerbCheck.cpp
    ${VS}/grammar/FinnishRuleEngine/MissingVerbCheck.cpp
    ${VS}/grammar/FinnishRuleEngine/NegativeVerbCheck.cpp
    ${VS}/grammar/FinnishRuleEngine/ParagraphCheck.cpp
    ${VS}/grammar/FinnishRuleEngine/SentenceCheck.cpp
    ${VS}/grammar/FinnishRuleEngine/SidesanaCheck.cpp
    ${VS}/grammar/FinnishRuleEngine/RelativePronounCheck.cpp
    ${VS}/compatibility/interface.cpp
    ${VS}/morphology/VfstAnalyzer.cpp
    ${VS}/morphology/FinnishVfstAnalyzer.cpp
    ${VS}/spellchecker/VfstSpeller.cpp
    ${VS}/spellchecker/VfstSuggestion.cpp
    ${VS}/setup/V5DictionaryLoader.cpp
    ${VS}/grammar/FinnishRuleEngine/VfstAutocorrectCheck.cpp)
target_include_directories(voikko PUBLIC ${VS})
# What configure would write into config.h, minus what the keyboard must not
# have: no DICTIONARY_PATH and DISABLE_EXTDICTS, so the library reads only
# the folder it is handed, never $HOME/.voikko or VOIKKO_DICTIONARY_PATH.
# HAVE_MMAP maps the transducers rather than copying them into the heap.
target_compile_definitions(voikko PUBLIC
    HAVE_VFST=1
    HAVE_MMAP=1
    DISABLE_EXTDICTS=1
    PACKAGE_VERSION="4.3.3"
    NDEBUG)
target_compile_options(voikko PRIVATE -Os -ffunction-sections -fdata-sections -fvisibility=hidden -w)

add_library(wmvoikko SHARED wm_voikko_jni.cpp)
target_link_libraries(wmvoikko PRIVATE voikko)
target_compile_options(wmvoikko PRIVATE -Os -fvisibility=hidden -ffunction-sections -fdata-sections)

if (WMVOIKKO_HOST)
    # A JDK's jni.h, or the NDK's (self-contained, the same API): build.sh
    # passes whichever it finds.
    set(JNI_INCLUDE "" CACHE PATH "Directory holding jni.h (and jni_md.h)")
    target_include_directories(wmvoikko PRIVATE "${JNI_INCLUDE}")
    foreach (os darwin linux)
        if (EXISTS "${JNI_INCLUDE}/${os}/jni_md.h")
            target_include_directories(wmvoikko PRIVATE "${JNI_INCLUDE}/${os}")
        endif()
    endforeach()
    add_executable(voikkovfstc ${VS}/tools/voikkovfstc.cpp)
    target_link_libraries(voikkovfstc PRIVATE voikko)
else()
    target_link_options(wmvoikko PRIVATE
        -Wl,--no-undefined
        -Wl,--gc-sections
        -Wl,--icf=all
        -Wl,--exclude-libs,ALL
        -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
        # Stripped at the link, so the build id hashes only the bytes that
        # ship: linked unstripped, two builds from different source paths
        # differed in those 20 bytes alone.
        -Wl,--strip-all
        -Wl,--build-id=sha1
        -Wl,-z,max-page-size=16384)
endif()
