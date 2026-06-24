## 1. Domain and Configuration Rename

- [x] 1.1 Search and rename `CaptainsLogChannel` to `JournalChannel` across the codebase.
- [x] 1.2 Update string resources in `res/values/strings.xml` (or equivalent location) changing "Captain's Log" to "Journal Channel".
- [x] 1.3 Update string resources changing "Save in log file" to "Save in journal file".
- [x] 1.4 Rename `captains_log` icons or assets to `journal` if applicable.

## 2. File and Output Renaming

- [x] 2.1 Update the markdown file generation logic to output `journal-YYYY-MM-DD.md` instead of `log-YYYY-MM-DD.md`.
- [x] 2.2 Update the markdown file header generation logic to write `# Journal YYYY-MM-DD`.
- [x] 2.3 Update the audio file naming prefix from `log-` to `journal-` (e.g. `journal-YYYY-MM-DD_HH-MM-SS.ogg`).
- [x] 2.4 Update markdown relative links to reference the correct `journal-` audio files.

## 3. Verification

- [x] 3.1 Verify the channel name appears correctly in the channel list UI.
- [x] 3.2 Verify that enabling "Save voice" generates a properly named `.ogg` file.
- [x] 3.3 Verify that enabling "Save in journal file" generates a properly named `.md` file with the correct header and content.
- [x] 3.4 Verify the project compiles and all tests pass.
