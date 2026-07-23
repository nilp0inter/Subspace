//! Typed host-operation broker registry.
//!
//! The Rust kernel is the validation and ownership boundary for generic host
//! calls. A host module validates Lua arguments, resolves userdata tokens, and
//! constructs a bounded immutable [`HostOperationPayload`] in a per-state
//! registry. Lua observes only an opaque request identity; the yielded label
//! carries no path, text, JSON, audio token, or provider argument.
//!
//! The host claims each request exactly once through typed JNI transport
//! ([`HostOperationPayload::to_claim_fields`]) and completes it through the
//! existing operation terminal gate. Payloads cannot be inspected or forged by
//! Lua after admission because they live only in this registry.

use serde_json::json;

use crate::outcome::Outcome;
use crate::ownership::OperationId;

/// Generic logical host-operation request kinds. These are host-internal
/// discriminants, never Lua-visible module or method names.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum HostOperationKind {
    Transcribe,
    Synthesize,
    Playback,
    AudioOpen,
    AudioExport,
    FsMkdir,
    FsStat,
    FsList,
    FsReadText,
    FsWriteText,
    FsRemove,
}

impl HostOperationKind {
    /// Stable wire token used in the typed claim result.
    pub fn as_str(self) -> &'static str {
        match self {
            HostOperationKind::Transcribe => "TRANSCRIBE",
            HostOperationKind::Synthesize => "SYNTHESIZE",
            HostOperationKind::Playback => "PLAYBACK",
            HostOperationKind::AudioOpen => "AUDIO_OPEN",
            HostOperationKind::AudioExport => "AUDIO_EXPORT",
            HostOperationKind::FsMkdir => "FS_MKDIR",
            HostOperationKind::FsStat => "FS_STAT",
            HostOperationKind::FsList => "FS_LIST",
            HostOperationKind::FsReadText => "FS_READ_TEXT",
            HostOperationKind::FsWriteText => "FS_WRITE_TEXT",
            HostOperationKind::FsRemove => "FS_REMOVE",
        }
    }

    /// True for filesystem operations that return structured JSON results.
    pub fn is_fs(self) -> bool {
        matches!(
            self,
            HostOperationKind::FsMkdir
                | HostOperationKind::FsStat
                | HostOperationKind::FsList
                | HostOperationKind::FsReadText
                | HostOperationKind::FsWriteText
                | HostOperationKind::FsRemove
        )
    }
}

/// Bounded immutable typed payload for one host operation.
///
/// Large arguments are explicit typed fields (or host-managed streams in later
/// request kinds); they are never concatenated into a yielded label.
#[derive(Clone, Debug, PartialEq)]
pub enum HostOperationPayload {
    Transcribe {
        audio_token: String,
    },
    Synthesize {
        text: String,
        language: String,
        voice: String,
        speed: f64,
    },
    Playback {
        audio_token: String,
        delay_seconds: f64,
    },
    AudioOpen {
        declaration_id: String,
        mount_token: String,
        path: String,
        format: String,
    },
    AudioExport {
        audio_token: String,
        declaration_id: String,
        mount_token: String,
        path: String,
        format: String,
        mode: String,
    },
    FsMkdir {
        declaration_id: String,
        mount_token: String,
        path: String,
        parents: bool,
    },
    FsStat {
        declaration_id: String,
        mount_token: String,
        path: String,
    },
    FsList {
        declaration_id: String,
        mount_token: String,
        path: String,
        limit: u32,
        cursor: Option<String>,
    },
    FsReadText {
        declaration_id: String,
        mount_token: String,
        path: String,
        max_bytes: u64,
    },
    FsWriteText {
        declaration_id: String,
        mount_token: String,
        path: String,
        text: String,
        mode: String,
    },
    FsRemove {
        declaration_id: String,
        mount_token: String,
        path: String,
        missing_ok: bool,
    },
}

impl HostOperationPayload {
    pub fn kind(&self) -> HostOperationKind {
        match self {
            HostOperationPayload::Transcribe { .. } => HostOperationKind::Transcribe,
            HostOperationPayload::Synthesize { .. } => HostOperationKind::Synthesize,
            HostOperationPayload::Playback { .. } => HostOperationKind::Playback,
            HostOperationPayload::AudioOpen { .. } => HostOperationKind::AudioOpen,
            HostOperationPayload::AudioExport { .. } => HostOperationKind::AudioExport,
            HostOperationPayload::FsMkdir { .. } => HostOperationKind::FsMkdir,
            HostOperationPayload::FsStat { .. } => HostOperationKind::FsStat,
            HostOperationPayload::FsList { .. } => HostOperationKind::FsList,
            HostOperationPayload::FsReadText { .. } => HostOperationKind::FsReadText,
            HostOperationPayload::FsWriteText { .. } => HostOperationKind::FsWriteText,
            HostOperationPayload::FsRemove { .. } => HostOperationKind::FsRemove,
        }
    }

    /// Append the typed payload fields to a claim outcome. No field is a
    /// concatenated label; each argument is its own bounded typed field.
    pub fn to_claim_fields(&self, outcome: Outcome) -> Outcome {
        match self {
            HostOperationPayload::Transcribe { audio_token } => {
                outcome.with("audioToken", json!(audio_token))
            }
            HostOperationPayload::Synthesize {
                text,
                language,
                voice,
                speed,
            } => outcome
                .with("text", json!(text))
                .with("language", json!(language))
                .with("voice", json!(voice))
                .with("speed", json!(speed)),
            HostOperationPayload::Playback {
                audio_token,
                delay_seconds,
            } => outcome
                .with("audioToken", json!(audio_token))
                .with("delaySeconds", json!(delay_seconds)),
            HostOperationPayload::AudioOpen {
                declaration_id,
                mount_token,
                path,
                format,
            } => outcome
                .with("declarationId", json!(declaration_id))
                .with("mountToken", json!(mount_token))
                .with("path", json!(path))
                .with("format", json!(format)),
            HostOperationPayload::AudioExport {
                audio_token,
                declaration_id,
                mount_token,
                path,
                format,
                mode,
            } => outcome
                .with("audioToken", json!(audio_token))
                .with("declarationId", json!(declaration_id))
                .with("mountToken", json!(mount_token))
                .with("path", json!(path))
                .with("format", json!(format))
                .with("mode", json!(mode)),
            HostOperationPayload::FsMkdir {
                declaration_id,
                mount_token,
                path,
                parents,
            } => outcome
                .with("declarationId", json!(declaration_id))
                .with("mountToken", json!(mount_token))
                .with("path", json!(path))
                .with("parents", json!(parents)),
            HostOperationPayload::FsStat {
                declaration_id,
                mount_token,
                path,
            } => outcome
                .with("declarationId", json!(declaration_id))
                .with("mountToken", json!(mount_token))
                .with("path", json!(path)),
            HostOperationPayload::FsList {
                declaration_id,
                mount_token,
                path,
                limit,
                cursor,
            } => {
                let outcome = outcome
                    .with("declarationId", json!(declaration_id))
                    .with("mountToken", json!(mount_token))
                    .with("path", json!(path))
                    .with("limit", json!(limit));
                match cursor {
                    Some(cursor) => outcome.with("cursor", json!(cursor)),
                    None => outcome,
                }
            }
            HostOperationPayload::FsReadText {
                declaration_id,
                mount_token,
                path,
                max_bytes,
            } => outcome
                .with("declarationId", json!(declaration_id))
                .with("mountToken", json!(mount_token))
                .with("path", json!(path))
                .with("maxBytes", json!(max_bytes)),
            HostOperationPayload::FsWriteText {
                declaration_id,
                mount_token,
                path,
                text,
                mode,
            } => outcome
                .with("declarationId", json!(declaration_id))
                .with("mountToken", json!(mount_token))
                .with("path", json!(path))
                .with("text", json!(text))
                .with("mode", json!(mode)),
            HostOperationPayload::FsRemove {
                declaration_id,
                mount_token,
                path,
                missing_ok,
            } => outcome
                .with("declarationId", json!(declaration_id))
                .with("mountToken", json!(mount_token))
                .with("path", json!(path))
                .with("missingOk", json!(missing_ok)),
        }
    }
}

/// One registered host-operation request in the per-state registry.
///
/// Created when a host module admits a validated Lua request, linked to its
/// yielded operation, claimed exactly once by the host dispatcher, and removed
/// when the owning coroutine resumes, cancels, or is released.
pub(crate) struct HostOperationEntry {
    pub payload: HostOperationPayload,
    pub coroutine_id: i64,
    pub operation_id: OperationId,
    pub claimed: bool,
}

impl HostOperationEntry {
    pub fn kind(&self) -> HostOperationKind {
        self.payload.kind()
    }
}
