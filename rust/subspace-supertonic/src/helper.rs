//! Supertonic helper: text preprocessing, chunking, masks, noisy-latent
//! sampling, and the four-session ONNX inference flow.
//!
//! Adapted from the upstream `supertone-inc/supertonic` reference (`rust/src/helper.rs`)
//! to the Subspace JNI surface: no CLI, no WAV writer, no file-based input/output.
//! The inference flow returns in-memory `f32` samples to the caller.

use std::path::Path;

use ndarray::{Array, Array1, Array3};
use ort::session::Session;
use ort::value::Value;
use rand::rngs::ThreadRng;
use rand_distr::{Distribution, Normal};
use regex::Regex;
use serde::{Deserialize, Serialize};
use unicode_normalization::UnicodeNormalization;

use crate::log;

/// Available languages for multilingual TTS. Matches the reference helper.
pub const AVAILABLE_LANGS: &[&str] = &[
    "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr", "hu",
    "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi", "na",
];

pub fn is_valid_lang(lang: &str) -> bool {
    AVAILABLE_LANGS.contains(&lang)
}

// ============================================================================
// Configuration structures
// ============================================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub ae: AeConfig,
    pub ttl: TtlConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AeConfig {
    pub sample_rate: i32,
    pub base_chunk_size: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TtlConfig {
    pub chunk_compress_factor: i32,
    pub latent_dim: i32,
}

pub fn load_cfgs(onnx_dir: &Path) -> Result<Config, HelperError> {
    let cfg_path = onnx_dir.join("tts.json");
    let file = std::fs::File::open(&cfg_path).map_err(|e| HelperError::ConfigLoad(e.to_string()))?;
    let cfgs: Config = serde_json::from_reader(file)
        .map_err(|e| HelperError::ConfigLoad(e.to_string()))?;
    Ok(cfgs)
}

// ============================================================================
// Voice style data
// ============================================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VoiceStyleData {
    pub style_ttl: StyleComponent,
    pub style_dp: StyleComponent,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StyleComponent {
    pub data: Vec<Vec<Vec<f32>>>,
    pub dims: Vec<usize>,
    #[serde(rename = "type")]
    pub dtype: String,
}

pub struct Style {
    pub ttl: Array3<f32>,
    pub dp: Array3<f32>,
}

pub fn load_voice_style(path: &Path) -> Result<Style, HelperError> {
    let file = std::fs::File::open(path).map_err(|e| HelperError::StyleLoad(e.to_string()))?;
    let reader = std::io::BufReader::new(file);
    let data: VoiceStyleData =
        serde_json::from_reader(reader).map_err(|e| HelperError::StyleLoad(e.to_string()))?;

    let ttl_dims = &data.style_ttl.dims;
    let dp_dims = &data.style_dp.dims;
    let ttl_dim1 = ttl_dims[1];
    let ttl_dim2 = ttl_dims[2];
    let dp_dim1 = dp_dims[1];
    let dp_dim2 = dp_dims[2];

    let mut ttl_flat = Vec::with_capacity(ttl_dim1 * ttl_dim2);
    for batch in &data.style_ttl.data {
        for row in batch {
            for &val in row {
                ttl_flat.push(val);
            }
        }
    }
    let mut dp_flat = Vec::with_capacity(dp_dim1 * dp_dim2);
    for batch in &data.style_dp.data {
        for row in batch {
            for &val in row {
                dp_flat.push(val);
            }
        }
    }

    let ttl_style = Array3::from_shape_vec((1, ttl_dim1, ttl_dim2), ttl_flat)
        .map_err(|e| HelperError::StyleLoad(format!("ttl shape: {e}")))?;
    let dp_style = Array3::from_shape_vec((1, dp_dim1, dp_dim2), dp_flat)
        .map_err(|e| HelperError::StyleLoad(format!("dp shape: {e}")))?;

    Ok(Style { ttl: ttl_style, dp: dp_style })
}

// ============================================================================
// Unicode text processor
// ============================================================================

pub struct UnicodeProcessor {
    indexer: Vec<i64>,
}

impl UnicodeProcessor {
    pub fn new(unicode_indexer_json_path: &Path) -> Result<Self, HelperError> {
        let file = std::fs::File::open(unicode_indexer_json_path)
            .map_err(|e| HelperError::IndexerLoad(e.to_string()))?;
        let reader = std::io::BufReader::new(file);
        let indexer: Vec<i64> = serde_json::from_reader(reader)
            .map_err(|e| HelperError::IndexerLoad(e.to_string()))?;
        Ok(UnicodeProcessor { indexer })
    }

    pub fn call(
        &self,
        text_list: &[String],
        lang_list: &[String],
    ) -> Result<(Vec<Vec<i64>>, Array3<f32>), HelperError> {
        let mut processed_texts: Vec<String> = Vec::new();
        for (text, lang) in text_list.iter().zip(lang_list.iter()) {
            processed_texts.push(preprocess_text(text, lang)?);
        }

        let text_ids_lengths: Vec<usize> = processed_texts
            .iter()
            .map(|t| t.chars().count())
            .collect();

        let max_len = *text_ids_lengths.iter().max().unwrap_or(&0);

        let mut text_ids = Vec::new();
        for text in &processed_texts {
            let mut row = vec![0i64; max_len];
            let unicode_vals = text_to_unicode_values(text);
            for (j, &val) in unicode_vals.iter().enumerate() {
                if val < self.indexer.len() {
                    row[j] = self.indexer[val];
                } else {
                    row[j] = -1;
                }
            }
            text_ids.push(row);
        }

        let text_mask = get_text_mask(&text_ids_lengths);

        Ok((text_ids, text_mask))
    }
}

pub fn preprocess_text(text: &str, lang: &str) -> Result<String, HelperError> {
    let mut text: String = text.nfkd().collect();

    let emoji_pattern = Regex::new(
        r"[\x{1F600}-\x{1F64F}\x{1F300}-\x{1F5FF}\x{1F680}-\x{1F6FF}\x{1F700}-\x{1F77F}\x{1F780}-\x{1F7FF}\x{1F800}-\x{1F8FF}\x{1F900}-\x{1F9FF}\x{1FA00}-\x{1FA6F}\x{1FA70}-\x{1FAFF}\x{2600}-\x{26FF}\x{2700}-\x{27BF}\x{1F1E6}-\x{1F1FF}]+",
    )
    .map_err(|e| HelperError::Preprocess(e.to_string()))?;
    text = emoji_pattern.replace_all(&text, "").to_string();

    let replacements = [
        ("\u{2013}", "-"),
        ("\u{2011}", "-"),
        ("\u{2014}", "-"),
        ("_", " "),
        ("\u{201C}", "\""),
        ("\u{201D}", "\""),
        ("\u{2018}", "'"),
        ("\u{2019}", "'"),
        ("´", "'"),
        ("`", "'"),
        ("[", " "),
        ("]", " "),
        ("|", " "),
        ("/", " "),
        ("#", " "),
        ("→", " "),
        ("←", " "),
    ];
    for (from, to) in &replacements {
        text = text.replace(from, to);
    }

    let special_symbols = ["♥", "☆", "♡", "©", "\\"];
    for symbol in &special_symbols {
        text = text.replace(symbol, "");
    }

    let expr_replacements = [
        ("@", " at "),
        ("e.g.,", "for example, "),
        ("i.e.,", "that is, "),
    ];
    for (from, to) in &expr_replacements {
        text = text.replace(from, to);
    }

    text = Regex::new(r" ,").unwrap().replace_all(&text, ",").to_string();
    text = Regex::new(r" \.").unwrap().replace_all(&text, ".").to_string();
    text = Regex::new(r" !").unwrap().replace_all(&text, "!").to_string();
    text = Regex::new(r" \?").unwrap().replace_all(&text, "?").to_string();
    text = Regex::new(r" ;").unwrap().replace_all(&text, ";").to_string();
    text = Regex::new(r" :").unwrap().replace_all(&text, ":").to_string();
    text = Regex::new(r" '").unwrap().replace_all(&text, "'").to_string();

    while text.contains("\"\"") {
        text = text.replace("\"\"", "\"");
    }
    while text.contains("''") {
        text = text.replace("''", "'");
    }
    while text.contains("``") {
        text = text.replace("``", "`");
    }

    text = Regex::new(r"\s+").unwrap().replace_all(&text, " ").to_string();
    text = text.trim().to_string();

    if !text.is_empty() {
        let ends_with_punct = Regex::new(r#"[.!?;:,'"\u{201C}\u{201D}\u{2018}\u{2019})\]}…。」』】〉》»›]$"#).unwrap();
        if !ends_with_punct.is_match(&text) {
            text.push('.');
        }
    }

    if !is_valid_lang(lang) {
        return Err(HelperError::InvalidLanguage {
            lang: lang.to_string(),
            available: AVAILABLE_LANGS.to_vec(),
        });
    }

    text = format!("<{}>{}</{}>", lang, text, lang);

    Ok(text)
}

pub fn text_to_unicode_values(text: &str) -> Vec<usize> {
    text.chars().map(|c| c as usize).collect()
}

pub fn length_to_mask(lengths: &[usize], max_len: Option<usize>) -> Array3<f32> {
    let bsz = lengths.len();
    let max_len = max_len.unwrap_or_else(|| *lengths.iter().max().unwrap_or(&0));

    let mut mask = Array3::<f32>::zeros((bsz, 1, max_len));
    for (i, &len) in lengths.iter().enumerate() {
        for j in 0..len.min(max_len) {
            mask[[i, 0, j]] = 1.0;
        }
    }
    mask
}

pub fn get_text_mask(text_ids_lengths: &[usize]) -> Array3<f32> {
    let max_len = *text_ids_lengths.iter().max().unwrap_or(&0);
    length_to_mask(text_ids_lengths, Some(max_len))
}

/// Sample noisy latent from a normal distribution and apply a length mask.
pub fn sample_noisy_latent(
    duration: &[f32],
    sample_rate: i32,
    base_chunk_size: i32,
    chunk_compress: i32,
    latent_dim: i32,
) -> (Array3<f32>, Array3<f32>) {
    let bsz = duration.len();
    let max_dur = duration.iter().fold(0.0f32, |a, &b| a.max(b));

    let wav_len_max = (max_dur * sample_rate as f32) as usize;
    let wav_lengths: Vec<usize> = duration
        .iter()
        .map(|&d| (d * sample_rate as f32) as usize)
        .collect();

    let chunk_size = (base_chunk_size * chunk_compress) as usize;
    let latent_len = (wav_len_max + chunk_size - 1) / chunk_size;
    let latent_dim_val = (latent_dim * chunk_compress) as usize;

    let mut noisy_latent = Array3::<f32>::zeros((bsz, latent_dim_val, latent_len));

    let normal = Normal::new(0.0, 1.0).unwrap();
    let mut rng: ThreadRng = rand::thread_rng();

    for b in 0..bsz {
        for d in 0..latent_dim_val {
            for t in 0..latent_len {
                noisy_latent[[b, d, t]] = normal.sample(&mut rng);
            }
        }
    }

    let latent_lengths: Vec<usize> = wav_lengths
        .iter()
        .map(|&len| (len + chunk_size - 1) / chunk_size)
        .collect();

    let latent_mask = length_to_mask(&latent_lengths, Some(latent_len));

    for b in 0..bsz {
        for d in 0..latent_dim_val {
            for t in 0..latent_len {
                noisy_latent[[b, d, t]] *= latent_mask[[b, 0, t]];
            }
        }
    }

    (noisy_latent, latent_mask)
}

// ============================================================================
// Text chunking
// ============================================================================

const MAX_CHUNK_LENGTH: usize = 300;

const ABBREVIATIONS: &[&str] = &[
    "Dr.", "Mr.", "Mrs.", "Ms.", "Prof.", "Sr.", "Jr.",
    "St.", "Ave.", "Rd.", "Blvd.", "Dept.", "Inc.", "Ltd.",
    "Co.", "Corp.", "etc.", "vs.", "i.e.", "e.g.", "Ph.D.",
];

pub fn chunk_text(text: &str, max_len: Option<usize>) -> Vec<String> {
    let max_len = max_len.unwrap_or(MAX_CHUNK_LENGTH);
    let text = text.trim();

    if text.is_empty() {
        return vec![String::new()];
    }

    let para_re = Regex::new(r"\n\s*\n").unwrap();
    let paragraphs: Vec<&str> = para_re.split(text).collect();
    let mut chunks = Vec::new();

    for para in paragraphs {
        let para = para.trim();
        if para.is_empty() {
            continue;
        }

        if para.len() <= max_len {
            chunks.push(para.to_string());
            continue;
        }

        let sentences = split_sentences(para);
        let mut current = String::new();
        let mut current_len = 0;

        for sentence in sentences {
            let sentence = sentence.trim();
            if sentence.is_empty() {
                continue;
            }

            let sentence_len = sentence.len();
            if sentence_len > max_len {
                if !current.is_empty() {
                    chunks.push(current.trim().to_string());
                    current.clear();
                    current_len = 0;
                }

                let parts: Vec<&str> = sentence.split(',').collect();
                for part in parts {
                    let part = part.trim();
                    if part.is_empty() {
                        continue;
                    }

                    let part_len = part.len();
                    if part_len > max_len {
                        let words: Vec<&str> = part.split_whitespace().collect();
                        let mut word_chunk = String::new();
                        let mut word_chunk_len = 0;

                        for word in words {
                            let word_len = word.len();
                            if word_chunk_len + word_len + 1 > max_len && !word_chunk.is_empty() {
                                chunks.push(word_chunk.trim().to_string());
                                word_chunk.clear();
                                word_chunk_len = 0;
                            }

                            if !word_chunk.is_empty() {
                                word_chunk.push(' ');
                                word_chunk_len += 1;
                            }
                            word_chunk.push_str(word);
                            word_chunk_len += word_len;
                        }

                        if !word_chunk.is_empty() {
                            chunks.push(word_chunk.trim().to_string());
                        }
                    } else {
                        if current_len + part_len + 1 > max_len && !current.is_empty() {
                            chunks.push(current.trim().to_string());
                            current.clear();
                            current_len = 0;
                        }

                        if !current.is_empty() {
                            current.push_str(", ");
                            current_len += 2;
                        }
                        current.push_str(part);
                        current_len += part_len;
                    }
                }
                continue;
            }

            if current_len + sentence_len + 1 > max_len && !current.is_empty() {
                chunks.push(current.trim().to_string());
                current.clear();
                current_len = 0;
            }

            if !current.is_empty() {
                current.push(' ');
                current_len += 1;
            }
            current.push_str(sentence);
            current_len += sentence_len;
        }

        if !current.is_empty() {
            chunks.push(current.trim().to_string());
        }
    }

    if chunks.is_empty() {
        vec![String::new()]
    } else {
        chunks
    }
}

fn split_sentences(text: &str) -> Vec<String> {
    let re = Regex::new(r"([.!?])\s+").unwrap();

    let matches: Vec<_> = re.find_iter(text).collect();
    if matches.is_empty() {
        return vec![text.to_string()];
    }

    let mut sentences = Vec::new();
    let mut last_end = 0;

    for m in matches {
        let before_punc = &text[last_end..m.start()];

        let mut is_abbrev = false;
        for abbrev in ABBREVIATIONS {
            let combined = format!(
                "{}{}",
                before_punc.trim(),
                &text[m.start()..m.start() + 1]
            );
            if combined.ends_with(abbrev) {
                is_abbrev = true;
                break;
            }
        }

        if !is_abbrev {
            sentences.push(text[last_end..m.end()].to_string());
            last_end = m.end();
        }
    }

    if last_end < text.len() {
        sentences.push(text[last_end..].to_string());
    }

    if sentences.is_empty() {
        vec![text.to_string()]
    } else {
        sentences
    }
}

// ============================================================================
// ONNX inference flow
// ============================================================================

/// Loaded Supertonic ONNX sessions and config used by [`synthesize`].
pub struct SupertonicSessions {
    pub cfgs: Config,
    pub text_processor: UnicodeProcessor,
    pub dp_ort: Session,
    pub text_enc_ort: Session,
    pub vector_est_ort: Session,
    pub vocoder_ort: Session,
}

#[derive(Debug, thiserror::Error)]
pub enum HelperError {
    #[error("config load failed: {0}")]
    ConfigLoad(String),
    #[error("indexer load failed: {0}")]
    IndexerLoad(String),
    #[error("style load failed: {0}")]
    StyleLoad(String),
    #[error("preprocessing failed: {0}")]
    Preprocess(String),
    #[error("invalid language `{lang}`; available: {available:?}")]
    InvalidLanguage { lang: String, available: Vec<&'static str> },
    #[error("ONNX error: {0}")]
    Onnx(String),
    #[error("shape error: {0}")]
    Shape(String),
    #[error("inference failed: {0}")]
    Inference(String),
}

fn ort_err<E: std::fmt::Display>(e: E) -> HelperError {
    HelperError::Onnx(e.to_string())
}

/// Run the four-session inference flow for a single chunk.
///
/// Returns `(wav_samples, chunk_duration_seconds)`.
fn infer_chunk(
    sessions: &mut SupertonicSessions,
    text: &str,
    lang: &str,
    style: &Style,
    total_step: usize,
    speed: f32,
) -> Result<(Vec<f32>, f32), HelperError> {
    let bsz = 1usize;

    let (text_ids, text_mask) = sessions
        .text_processor
        .call(&[text.to_string()], &[lang.to_string()])?;

    let text_ids_array = {
        let shape = (bsz, text_ids[0].len());
        let mut flat = Vec::new();
        for row in &text_ids {
            flat.extend_from_slice(row);
        }
        Array::from_shape_vec(shape, flat).map_err(|e| HelperError::Shape(e.to_string()))?
    };

    let text_ids_value = Value::from_array(text_ids_array.clone()).map_err(ort_err)?;
    let text_mask_value = Value::from_array(text_mask.clone()).map_err(ort_err)?;
    let style_dp_value = Value::from_array(style.dp.clone()).map_err(ort_err)?;

    // Duration predictor.
    let dp_outputs = sessions
        .dp_ort
        .run(ort::inputs! {
            "text_ids" => &text_ids_value,
            "style_dp" => &style_dp_value,
            "text_mask" => &text_mask_value
        })
        .map_err(ort_err)?;

    let (_, duration_data) = dp_outputs["duration"]
        .try_extract_tensor::<f32>()
        .map_err(ort_err)?;
    let mut duration: Vec<f32> = duration_data.to_vec();

    for dur in duration.iter_mut() {
        *dur /= speed;
    }

    // Text encoder.
    let style_ttl_value = Value::from_array(style.ttl.clone()).map_err(ort_err)?;
    let text_enc_outputs = sessions
        .text_enc_ort
        .run(ort::inputs! {
            "text_ids" => &text_ids_value,
            "style_ttl" => &style_ttl_value,
            "text_mask" => &text_mask_value
        })
        .map_err(ort_err)?;

    let (text_emb_shape, text_emb_data) = text_enc_outputs["text_emb"]
        .try_extract_tensor::<f32>()
        .map_err(ort_err)?;
    let text_emb = Array3::from_shape_vec(
        (
            text_emb_shape[0] as usize,
            text_emb_shape[1] as usize,
            text_emb_shape[2] as usize,
        ),
        text_emb_data.to_vec(),
    )
    .map_err(|e| HelperError::Shape(e.to_string()))?;

    // Noisy latent.
    let (mut xt, latent_mask) = sample_noisy_latent(
        &duration,
        sessions.cfgs.ae.sample_rate,
        sessions.cfgs.ae.base_chunk_size,
        sessions.cfgs.ttl.chunk_compress_factor,
        sessions.cfgs.ttl.latent_dim,
    );

    let total_step_array: Array1<f32> = Array::from_elem(bsz, total_step as f32);

    // Denoising loop.
    for step in 0..total_step {
        let current_step_array: Array1<f32> = Array::from_elem(bsz, step as f32);

        let xt_value = Value::from_array(xt.clone()).map_err(ort_err)?;
        let text_emb_value = Value::from_array(text_emb.clone()).map_err(ort_err)?;
        let latent_mask_value = Value::from_array(latent_mask.clone()).map_err(ort_err)?;
        let text_mask_value2 = Value::from_array(text_mask.clone()).map_err(ort_err)?;
        let current_step_value = Value::from_array(current_step_array).map_err(ort_err)?;
        let total_step_value = Value::from_array(total_step_array.clone()).map_err(ort_err)?;

        let vector_est_outputs = sessions
            .vector_est_ort
            .run(ort::inputs! {
                "noisy_latent" => &xt_value,
                "text_emb" => &text_emb_value,
                "style_ttl" => &style_ttl_value,
                "latent_mask" => &latent_mask_value,
                "text_mask" => &text_mask_value2,
                "current_step" => &current_step_value,
                "total_step" => &total_step_value
            })
            .map_err(ort_err)?;

        let (denoised_shape, denoised_data) = vector_est_outputs["denoised_latent"]
            .try_extract_tensor::<f32>()
            .map_err(ort_err)?;
        xt = Array3::from_shape_vec(
            (
                denoised_shape[0] as usize,
                denoised_shape[1] as usize,
                denoised_shape[2] as usize,
            ),
            denoised_data.to_vec(),
        )
        .map_err(|e| HelperError::Shape(e.to_string()))?;
    }

    // Vocoder.
    let final_latent_value = Value::from_array(xt).map_err(ort_err)?;
    let vocoder_outputs = sessions
        .vocoder_ort
        .run(ort::inputs! {
            "latent" => &final_latent_value
        })
        .map_err(ort_err)?;

    let (_, wav_data) = vocoder_outputs["wav_tts"]
        .try_extract_tensor::<f32>()
        .map_err(ort_err)?;
    let wav: Vec<f32> = wav_data.to_vec();

    let chunk_duration = duration[0];
    Ok((wav, chunk_duration))
}

/// Synthesize speech for `text` using the loaded sessions and voice style.
///
/// Splits the text into chunks (language-specific max length), synthesizes
/// each chunk, inserts a short silence between chunks, and returns the
/// concatenated 44.1 kHz mono `f32` samples.
pub fn synthesize(
    sessions: &mut SupertonicSessions,
    text: &str,
    lang: &str,
    style: &Style,
    total_step: usize,
    speed: f32,
    silence_duration: f32,
) -> Result<Vec<f32>, HelperError> {
    if !is_valid_lang(lang) {
        return Err(HelperError::InvalidLanguage {
            lang: lang.to_string(),
            available: AVAILABLE_LANGS.to_vec(),
        });
    }
    if text.trim().is_empty() {
        return Ok(Vec::new());
    }

    let max_len = if lang == "ko" || lang == "ja" { 120 } else { 300 };
    let chunks = chunk_text(text, Some(max_len));

    let sample_rate = sessions.cfgs.ae.sample_rate;
    let mut wav_cat: Vec<f32> = Vec::new();

    for (i, chunk) in chunks.iter().enumerate() {
        log::debug(&format!("synthesizing chunk {}/{}", i + 1, chunks.len()));
        let (wav, duration) = infer_chunk(sessions, chunk, lang, style, total_step, speed)?;

        let wav_len = (sample_rate as f32 * duration) as usize;
        let wav_chunk = &wav[..wav_len.min(wav.len())];

        if i == 0 {
            wav_cat.extend_from_slice(wav_chunk);
        } else {
            let silence_len = (silence_duration * sample_rate as f32) as usize;
            wav_cat.extend(std::iter::repeat(0.0f32).take(silence_len));
            wav_cat.extend_from_slice(wav_chunk);
        }
    }

    Ok(wav_cat)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chunk_text_short_returns_single_chunk() {
        let chunks = chunk_text("Hello world.", None);
        assert_eq!(chunks.len(), 1);
        assert_eq!(chunks[0], "Hello world.");
    }

    #[test]
    fn chunk_text_empty_returns_single_empty_chunk() {
        let chunks = chunk_text("   ", None);
        assert_eq!(chunks, vec![String::new()]);
    }

    #[test]
    fn chunk_text_long_text_splits_on_sentences() {
        let mut long = String::new();
        for _ in 0..10 {
            long.push_str("This is a fairly long sentence that should exceed the limit. ");
        }
        let chunks = chunk_text(&long, Some(80));
        assert!(chunks.len() > 1);
        for chunk in &chunks {
            assert!(chunk.len() <= 80 || chunk.split_whitespace().count() == 1,
                "chunk too long: {} bytes", chunk.len());
        }
    }

    #[test]
    fn preprocess_text_wraps_with_lang_tag() {
        let out = preprocess_text("hello", "en").unwrap();
        assert!(out.starts_with("<en>"));
        assert!(out.ends_with("</en>"));
        assert!(out.contains("hello"));
    }

    #[test]
    fn preprocess_text_rejects_invalid_language() {
        let err = preprocess_text("hello", "xx").unwrap_err();
        assert!(matches!(err, HelperError::InvalidLanguage { .. }));
    }

    #[test]
    fn preprocess_text_adds_terminal_punctuation_when_missing() {
        let out = preprocess_text("hello", "en").unwrap();
        // The lang tag wraps the text, so inside the tags the text ends with '.'
        let inner = out
            .trim_start_matches("<en>")
            .trim_end_matches("</en>");
        assert!(inner.ends_with('.'));
    }

    #[test]
    fn preprocess_text_preserves_existing_terminal_punctuation() {
        let out = preprocess_text("hello!", "en").unwrap();
        let inner = out
            .trim_start_matches("<en>")
            .trim_end_matches("</en>");
        assert!(inner.ends_with('!'));
    }

    #[test]
    fn text_to_unicode_values_maps_ascii() {
        let vals = text_to_unicode_values("AB");
        assert_eq!(vals, vec![65, 66]);
    }

    #[test]
    fn get_text_mask_returns_correct_shape() {
        let mask = get_text_mask(&[3, 5]);
        assert_eq!(mask.shape(), [2, 1, 5]);
        assert_eq!(mask[[0, 0, 2]], 1.0);
        assert_eq!(mask[[0, 0, 4]], 0.0);
        assert_eq!(mask[[1, 0, 4]], 1.0);
    }

    #[test]
    fn length_to_mask_with_explicit_max_len() {
        let mask = length_to_mask(&[2, 3], Some(4));
        assert_eq!(mask.shape(), [2, 1, 4]);
        assert_eq!(mask[[0, 0, 0]], 1.0);
        assert_eq!(mask[[0, 0, 1]], 1.0);
        assert_eq!(mask[[0, 0, 2]], 0.0);
        assert_eq!(mask[[1, 0, 2]], 1.0);
        assert_eq!(mask[[1, 0, 3]], 0.0);
    }

    #[test]
    fn sample_noisy_latent_has_expected_shape() {
        let (noisy, mask) = sample_noisy_latent(&[1.0], 44_100, 8_192, 4, 64);
        assert!(noisy.shape()[0] == 1);
        assert!(noisy.shape()[2] > 0);
        assert_eq!(mask.shape()[0], 1);
    }

    #[test]
    fn is_valid_lang_accepts_known_codes() {
        assert!(is_valid_lang("en"));
        assert!(is_valid_lang("ko"));
        assert!(is_valid_lang("ja"));
        assert!(!is_valid_lang("xx"));
    }
}