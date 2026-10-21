//! Deflates the Khipro specs into `OUT_DIR`. The four `.mim` files are
//! 196 KB of text, mostly Bengali and comments, and about 30 KB deflated;
//! `khipro::Variant::spec` inflates one the first time it is used. The files
//! under `data/khipro/` stay verbatim, as `scripts/update-khipro.sh` fetches
//! them.

use std::path::PathBuf;
use std::{env, fs};

const KHIPRO_SPECS: [&str; 4] =
    ["bn-khipro-touchscreen.mim", "bn-khipro.mim", "bn-khipro-dark-touchscreen.mim", "bn-khipro-dark.mim"];

fn main() {
    let out = PathBuf::from(env::var_os("OUT_DIR").expect("OUT_DIR"));
    println!("cargo:rerun-if-changed=build.rs");
    for name in KHIPRO_SPECS {
        let path = format!("data/khipro/{name}");
        println!("cargo:rerun-if-changed={path}");
        let text = fs::read(&path).unwrap_or_else(|e| panic!("{path}: {e}"));
        let packed = miniz_oxide::deflate::compress_to_vec(&text, 10);
        fs::write(out.join(format!("{name}.deflate")), packed).expect("write the deflated spec");
    }
}
