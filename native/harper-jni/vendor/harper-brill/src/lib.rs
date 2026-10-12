use std::num::NonZero;
use std::rc::Rc;
use std::sync::{Arc, LazyLock};

pub use harper_pos_utils::{
    BrillChunker, BrillTagger, BurnChunkerCpu, CachedChunker, Chunker, FreqDict, Tagger, UPOS,
};

// WM Keyboard: the trained models are stored deflated and inflated once, on
// first use, which takes 1.4 MB off the .so (see native/harper-jni/README.md).
fn inflate(bytes: &[u8]) -> Vec<u8> {
    miniz_oxide::inflate::decompress_to_vec(bytes).expect("embedded model is not valid deflate data")
}

const BRILL_TAGGER_SOURCE: &[u8] = include_bytes!("../trained_tagger_model.json.deflate");

static BRILL_TAGGER: LazyLock<Arc<BrillTagger<FreqDict>>> =
    LazyLock::new(|| Arc::new(uncached_brill_tagger()));

fn uncached_brill_tagger() -> BrillTagger<FreqDict> {
    serde_json::from_slice(&inflate(BRILL_TAGGER_SOURCE)).unwrap()
}

/// Get a copy of a shared, lazily-initialized [`BrillTagger`]. There will be only one instance
/// per-process.
pub fn brill_tagger() -> Arc<BrillTagger<FreqDict>> {
    (*BRILL_TAGGER).clone()
}

const BRILL_CHUNKER_SOURCE: &[u8] = include_bytes!("../trained_chunker_model.json.deflate");

static BRILL_CHUNKER: LazyLock<Arc<BrillChunker>> =
    LazyLock::new(|| Arc::new(uncached_brill_chunker()));

fn uncached_brill_chunker() -> BrillChunker {
    serde_json::from_slice(&inflate(BRILL_CHUNKER_SOURCE)).unwrap()
}

/// Get a copy of a shared, lazily-initialized [`BrillChunker`]. There will be only one instance
/// per-process.
pub fn brill_chunker() -> Arc<BrillChunker> {
    (*BRILL_CHUNKER).clone()
}

const BURN_CHUNKER_VOCAB: &[u8] = include_bytes!("../finished_chunker/vocab.json.deflate");
const BURN_CHUNKER_BIN: &[u8] = include_bytes!("../finished_chunker/model.mpk.deflate");

thread_local! {
    static BURN_CHUNKER: Rc<CachedChunker<BurnChunkerCpu>> =  Rc::new(uncached_burn_chunker());
}

fn uncached_burn_chunker() -> CachedChunker<BurnChunkerCpu> {
    CachedChunker::new(
        BurnChunkerCpu::load_from_bytes_cpu(&inflate(BURN_CHUNKER_BIN), &inflate(BURN_CHUNKER_VOCAB), 6, 0.3),
        NonZero::new(10000).unwrap(),
    )
}

/// Get a copy of a shared, lazily-initialized [`BurnChunkerCpu`]. There will be only one instance
/// per-process. Since neural net inference is extremely expensive, this chunker is memoized as
/// well.
pub fn burn_chunker() -> Rc<CachedChunker<BurnChunkerCpu>> {
    (BURN_CHUNKER).with(|c| c.clone())
}
