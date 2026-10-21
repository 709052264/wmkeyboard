//! How well `segment::words` finds the words in Thai, Lao, Khmer and Burmese
//! text, measured on the data repo's word lists.
//!
//! No segmented corpus of these languages is in reach, so the text is made
//! from the lists: runs of two to six list words, drawn by frequency and
//! written together with no spaces, as the languages are. A boundary between
//! two drawn words is a true boundary; the segmenter is scored on finding
//! those (recall), on cutting nowhere else (precision), and on giving back
//! every word of a run exactly. Words drawn side by side at random are not
//! real sentences, so this reads the LSTM a little out of its element, and a
//! list word the segmenter cuts in two counts against it even where the list
//! itself is a compound.
//!
//! Ignored by default. To run, put each list's top words, uncompressed, at
//! `$SEGMENT_EVAL_DIR/<code>.txt` (`gzcat data/th/th_full.txt.gz | head -n
//! 20000 > th.txt`) and run `cargo test --release --test segment_eval --
//! --ignored --nocapture`.

use std::time::Instant;
use wm_engine::segment;

/// A small deterministic generator, so every run draws the same text.
struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }

    fn below(&mut self, n: usize) -> usize {
        (self.next() % n as u64) as usize
    }
}

/// The list's words that are letters of the segmented scripts throughout,
/// with their frequencies.
fn read(path: &std::path::Path) -> Vec<(String, u64)> {
    let text = std::fs::read_to_string(path).expect("list");
    text.lines()
        .filter_map(|line| {
            let mut parts = line.split_whitespace();
            let word = parts.next()?;
            let count = parts.next().and_then(|c| c.parse().ok()).unwrap_or(1);
            word.chars()
                .all(|c| segment::is_segmented_script(c as u32))
                .then(|| (word.to_string(), count))
        })
        .collect()
}

#[test]
#[ignore]
fn segment_eval() {
    let Some(dir) = std::env::var_os("SEGMENT_EVAL_DIR") else {
        eprintln!("SEGMENT_EVAL_DIR not set");
        return;
    };
    let dir = std::path::PathBuf::from(dir);
    println!("| Language | List words used | Runs | Boundary precision | Boundary recall | F1 | Runs exact | Words exact | µs per char |");
    println!("|---|---|---|---|---|---|---|---|---|");
    for code in ["th", "lo", "km", "my"] {
        let path = dir.join(format!("{code}.txt"));
        if !path.is_file() {
            continue;
        }
        let words = read(&path);
        // Draw by frequency over the top 5,000: cumulative weights.
        let pool: Vec<&(String, u64)> = words.iter().take(5000).collect();
        let total: u64 = pool.iter().map(|w| w.1.max(1)).sum();
        let mut cumulative = Vec::with_capacity(pool.len());
        let mut acc = 0;
        for w in &pool {
            acc += w.1.max(1);
            cumulative.push(acc);
        }
        let mut rng = Rng(0x9E37_79B9_7F4A_7C15 ^ code.len() as u64);
        let (mut tp, mut fp, mut fn_, mut exact_runs, mut exact_words, mut gold_words) = (0, 0, 0, 0, 0, 0);
        let (mut chars, mut elapsed) = (0usize, 0f64);
        let runs = 4000;
        for _ in 0..runs {
            let n = 2 + rng.below(5);
            let drawn: Vec<&str> = (0..n)
                .map(|_| {
                    let r = rng.next() % total;
                    let i = cumulative.partition_point(|&c| c <= r);
                    pool[i].0.as_str()
                })
                .collect();
            let text: String = drawn.concat();
            let units: Vec<u16> = text.encode_utf16().collect();
            let mut gold = Vec::new();
            let mut at = 0;
            for w in &drawn {
                at += w.encode_utf16().count();
                gold.push(at);
            }
            gold.pop();
            let start = Instant::now();
            let got = segment::words(&units);
            elapsed += start.elapsed().as_secs_f64();
            chars += units.len();
            let cuts: Vec<usize> = got.iter().skip(1).map(|r| r.0).collect();
            let hit = cuts.iter().filter(|c| gold.contains(c)).count();
            tp += hit;
            fp += cuts.len() - hit;
            fn_ += gold.len() - hit;
            if cuts == gold {
                exact_runs += 1;
            }
            // A gold word comes back exactly when both its edges are cuts.
            let mut edges = vec![0];
            edges.extend(&gold);
            edges.push(units.len());
            let mut found = vec![0];
            found.extend(&cuts);
            found.push(units.len());
            for pair in edges.windows(2) {
                gold_words += 1;
                if found.contains(&pair[0]) && found.contains(&pair[1]) && !found.iter().any(|&c| c > pair[0] && c < pair[1]) {
                    exact_words += 1;
                }
            }
        }
        let precision = tp as f64 / (tp + fp).max(1) as f64;
        let recall = tp as f64 / (tp + fn_).max(1) as f64;
        let f1 = 2.0 * precision * recall / (precision + recall).max(f64::EPSILON);
        println!(
            "| {code} | {} | {runs} | {precision:.3} | {recall:.3} | {f1:.3} | {:.3} | {:.3} | {:.2} |",
            pool.len(),
            exact_runs as f64 / runs as f64,
            exact_words as f64 / gold_words as f64,
            elapsed * 1e6 / chars as f64,
        );
    }
}
