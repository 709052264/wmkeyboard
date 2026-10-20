//! Host check against real archives:
//!   cargo run --release --example probe -- <archive.bhfst> word...
//! Prints, per word, whether it is accepted, its spellings and the time each took.
use std::time::Instant;

fn main() {
    let mut args = std::env::args().skip(1);
    let path = args.next().expect("archive path");
    let t = Instant::now();
    let checker = wmdivvun::Checker::open(&path).expect("open");
    println!("opened {path} in {:?}", t.elapsed());
    for word in args {
        let t = Instant::now();
        let ok = checker.is_correct(&word);
        let check = t.elapsed();
        let t = Instant::now();
        let spellings = if ok { Vec::new() } else { checker.suggest(&word, 5) };
        println!("{word}\t{}\t{check:?}\t{:?}\t{spellings:?}", if ok { "accept" } else { "reject" }, t.elapsed());
    }
}
