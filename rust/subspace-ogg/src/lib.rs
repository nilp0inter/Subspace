//! Subspace OGG/Vorbis encoder bridge.
//!
//! Encodes signed 16-bit, 16 kHz, mono PCM to an OGG/Vorbis file through JNI.

#![allow(clippy::missing_safety_doc)]

use std::fs::File;
use std::num::{NonZeroU32, NonZeroU8};
use std::path::{Path, PathBuf};

use jni::objects::{JClass, JShortArray, JString};
use jni::sys::jint;
use jni::JNIEnv;
use thiserror::Error;
use vorbis_rs::{VorbisBitrateManagementStrategy, VorbisEncoderBuilder};

const CHANNELS: u32 = 1;
const EXPECTED_SAMPLE_RATE: i32 = 16_000;

#[no_mangle]
pub unsafe extern "C" fn Java_dev_nilp0inter_subspace_audio_OggJniBridge_nativeEncode(
    mut env: JNIEnv,
    _class: JClass,
    samples_array: JShortArray,
    sample_rate: jint,
    output_path: JString,
) -> jint {
    let result = (|| {
        let samples = extract_i16_samples(&mut env, &samples_array)?;
        let path = extract_path(&mut env, &output_path)?;
        encode_ogg_vorbis(&samples, sample_rate, &path)
    })();

    match result {
        Ok(()) => 0,
        Err(err) => {
            eprintln!("subspace_ogg encode failed: {err}");
            -1
        }
    }
}

fn extract_i16_samples(env: &mut JNIEnv, array: &JShortArray) -> Result<Vec<i16>, OggError> {
    let length = env.get_array_length(array).map_err(|_| OggError::InvalidArray)?;
    if length < 0 {
        return Err(OggError::InvalidArray);
    }
    let mut buf = vec![0i16; length as usize];
    env.get_short_array_region(array, 0, &mut buf)
        .map_err(|_| OggError::InvalidArray)?;
    Ok(buf)
}

fn extract_path(env: &mut JNIEnv, path: &JString) -> Result<PathBuf, OggError> {
    let string: String = env
        .get_string(path)
        .map_err(|_| OggError::InvalidPath)?
        .into();
    Ok(PathBuf::from(string))
}

pub fn encode_ogg_vorbis(samples: &[i16], sample_rate: i32, output_path: &Path) -> Result<(), OggError> {
    if sample_rate != EXPECTED_SAMPLE_RATE {
        return Err(OggError::UnsupportedSampleRate(sample_rate));
    }
    if samples.is_empty() {
        return Err(OggError::EmptyInput);
    }

    if let Some(parent) = output_path.parent() {
        std::fs::create_dir_all(parent)?;
    }

    let output = File::create(output_path)?;
    let mut encoder = VorbisEncoderBuilder::new_with_serial(
        NonZeroU32::new(sample_rate as u32).expect("validated nonzero sample rate"),
        NonZeroU8::new(CHANNELS as u8).expect("validated nonzero channel count"),
        output,
        0x5355_4245,
    )
    .bitrate_management_strategy(VorbisBitrateManagementStrategy::QualityVbr { target_quality: 0.35 })
    .build()?;

    let pcm: Vec<f32> = samples.iter().map(|sample| *sample as f32 / 32768.0).collect();
    encoder.encode_audio_block([pcm.as_slice()])?;
    encoder.finish()?;
    Ok(())
}

#[derive(Debug, Error)]
pub enum OggError {
    #[error("invalid Java short array")]
    InvalidArray,
    #[error("invalid output path")]
    InvalidPath,
    #[error("unsupported sample rate: {0}")]
    UnsupportedSampleRate(i32),
    #[error("empty PCM input")]
    EmptyInput,
    #[error("I/O failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("Vorbis encoding failed: {0}")]
    Vorbis(#[from] vorbis_rs::VorbisError),
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn synthetic_pcm_produces_ogg_file() {
        let dir = tempfile::tempdir().unwrap();
        let output = dir.path().join("synthetic.ogg");
        let samples: Vec<i16> = (0..EXPECTED_SAMPLE_RATE)
            .map(|i| {
                let phase = 2.0 * std::f64::consts::PI * 440.0 * i as f64 / EXPECTED_SAMPLE_RATE as f64;
                (phase.sin() * i16::MAX as f64 * 0.25) as i16
            })
            .collect();

        encode_ogg_vorbis(&samples, EXPECTED_SAMPLE_RATE, &output).unwrap();

        let bytes = std::fs::read(output).unwrap();
        assert!(bytes.len() > 4);
        assert_eq!(&bytes[..4], b"OggS");
    }
}
