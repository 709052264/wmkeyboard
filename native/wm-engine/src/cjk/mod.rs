//! The phone's Chinese, Cantonese and Japanese conversion engines (the
//! Kotlin `core/input/composer` CJK family): the reading dictionary and word
//! pairs, the lattice decoder every composer shares, the readings each method
//! turns a buffer into, and [`engine::Cjk`], the composers over one state.

pub mod dict;
pub mod engine;
pub mod han;
pub mod history;
pub mod jyutping;
pub mod kana;
pub mod lattice;
pub mod ngrams;
pub mod pinyin;
pub mod segment;
