## MODIFIED Requirements

### Requirement: Phrase fingerprints and manifests use byte-exact schema version 1
The system SHALL compute the Supertonic manifest identity by hashing canonical bytes consisting of a big-endian 4-byte manifest-version length and UTF-8 version, a big-endian 4-byte file count, and each `ModelSetHash.files` path and raw SHA-256 bytes sorted by unsigned UTF-8 path bytes. The system SHALL compute each phrase fingerprint by hashing, in order, schema version `Int32(1)`, package `lastUpdateTime` `Int64`, phrase UTF-8 length and bytes, raw 32-byte Supertonic manifest hash, raw 32-byte selected-style hash, language UTF-8 length and bytes, `Int32(4)`, `Float.floatToRawIntBits(1.2f)`, `Int32(16000)`, the UTF-8 encoding literal `pcm16le-wav`, `Int32(1)` channel count, and `Int32(16)` bits per sample, with every integer big-endian. The `Int32(4)` field is both the canonical announcement synthesis step count and part of persistent cache identity.

The version-1 `manifest.json` SHALL contain exactly this shape and no additional fields:

```json
{
  "schemaVersion": 1,
  "logicalEntries": [
    {"key": "string", "fingerprint": "lowercase-64-hex"}
  ],
  "files": [
    {
      "fingerprint": "lowercase-64-hex",
      "file": "<fingerprint>.wav",
      "fileSha256": "lowercase-64-hex",
      "sampleRate": 16000,
      "channelCount": 1,
      "bitsPerSample": 16,
      "sampleCount": 1
    }
  ]
}
```

The system SHALL sort `logicalEntries` by unsigned UTF-8 key bytes and `files` by raw fingerprint bytes before writing, and SHALL require the file-record fingerprint set to equal the distinct logical-entry fingerprint set.

#### Scenario: Golden identity encoding is stable
- **WHEN** the same verified model records, package update identity, phrase, selected style, and four-step render settings are encoded twice
- **THEN** the canonical byte sequences are identical
- **AND** the resulting SHA-256 fingerprints are identical lowercase 64-hex strings
- **AND** changing only the logical announcement key does not change the fingerprint

#### Scenario: Prior higher-step cache entries are invalidated
- **WHEN** a persisted phrase was fingerprinted with a synthesis step count other than four
- **THEN** the four-step render settings SHALL produce a distinct fingerprint
- **AND** the prior phrase SHALL be treated as a cache miss and swept after successful reconciliation
- **AND** the current phrase SHALL be synthesized with exactly four steps

#### Scenario: Malformed identity metadata disables admission
- **WHEN** the package update identity is absent, zero, or unreadable, or a required model/style SHA-256 is absent, malformed, or ambiguous because verified style records duplicate a basename
- **THEN** the system performs in-memory synthesis without loading persisted hits
- **AND** the system does not persist a selected-style phrase
- **AND** bootstrap readiness is not failed solely because persistent identity is unavailable
