# Physical acceptance evidence

Date: 2026-07-23

## Accepted app and package identities

- Device: `CPH2653`, Android API 35; RSM target `B02PTT-FF01`.
- App: `dev.nilp0inter.subspace`, version code `7`, version `0.7.0`.
- Source HEAD: `93ff964c2911be058aca3f03d88b5a71f2aee792`, with the
  change implementation present in the working tree.
- Installed debug APK SHA-256:
  `6f496a9e3ea7d8b668818323cf6809cca43eb955a0e82e4228e950247f7e4e8a`.
- External implementation: `github-repository:1309332087`.
- Repository: `nilp0inter/journal-channel`; repository ID `1309332087`;
  owner ID `1224006`.
- Active release: `v1.0.1`; release ID `358535259`; asset ID `486933457`;
  asset `subspace-channel.zip`; SHA-256
  `54b05b21089db5abb63e6a2dceb70aec36e64407a7e18f8438fe42da41ce506a`.
- Retained rollback: `v1.0.0`; release ID `358395794`; asset ID
  `486586844`; SHA-256
  `6d4e86ed2f1a82c2c05c919350ec0aa039f92f0ebb682373bef836c6529fc9fa`.

## Instances and physically distinct roots

- Built-in `Journal`: instance `captains-log`, implementation
  `builtin:journal`, raw root `/storage/emulated/0/Recordings/Log`.
- External `Journal Lua Channel`: instance
  `3bb7b9f3-752d-4e41-9e4a-3814dbac1f10`, mount `output`, SAF tree
  `primary:Recordings/Journal`.
- External `Journal2`: instance
  `a69e8a8d-c15f-4c25-ac05-f8016c3d29ff`, mount `output`, SAF tree
  `primary:Recordings/Lua Journal`.
- Both external bindings were persisted as active, read-write, and available.
  The app restored both independently after process restart.
- Grant revocation made the affected external instance unavailable without
  changing the other instance. Generic mount re-selection repaired it and
  created a fresh runtime generation.

## Captures and terminal ordering

- Generation 3, `Journal Lua Channel`, `VOICE_AND_TRANSCRIPT`: entry
  `journal-entry-2026-07-23_13-53-16-117+0200`. Input committed before
  deferred transcription; transcription succeeded; the capture spool was
  cleaned at sequence 6.
- Generation 4, `Journal2`, `TRANSCRIPT`: entry
  `journal-entry-2026-07-23_13-53-23-778+0200`. Input committed before
  deferred transcription; transcription succeeded; the capture spool was
  cleaned at sequence 4.
- Generation 5, `Journal Lua Channel`, `VOICE`: entry
  `journal-entry-2026-07-23_13-59-23-244+0200`. Input committed before
  deferred OGG work; the capture spool was cleaned at sequence 4.
- Pending durable capture state was rediscovered after restart. Recovery ran
  outside input and retained generation ownership; later captures remained
  usable after isolated recovery interruption.
- The built-in `captains-log` PTT dispatched and completed through its native
  provider while the external provider remained installed and ready.

## Artifact and isolation observations

- `VOICE_AND_TRANSCRIPT` produced OGG, transcript/state, and daily Markdown.
- `TRANSCRIPT` produced transcript/state and daily Markdown without an OGG.
- `VOICE` produced OGG/state and daily Markdown without transcription.
- The operator confirmed each output only appeared in its selected root.
- The built-in capture only appeared under `Recordings/Log`; neither external
  SAF tree received built-in output.
- The two external instances retained separate actors, generations, bindings,
  state, entry files, readiness, and recovery work.
- Revoked, replaced, and interrupted generations did not receive late success
  publication. Background-task interruption was recorded diagnostically and
  did not fail the current program image.
