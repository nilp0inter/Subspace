## Context

The system currently includes a feature called "Captain's log channel" that records voice and transcribes it to a daily markdown file. The name "Captain's log" is overly specific and somewhat whimsical. Renaming it to "Journal Channel" makes the terminology more standard, intuitive, and consistent with conventional app naming for personal recording/logging features.

## Goals / Non-Goals

**Goals:**
- Rename all user-facing instances of "Captain's log" or "Captain's log channel" to "Journal" or "Journal Channel".
- Update internal nomenclature (code symbols, variables, class names, file prefixes) to use "Journal".
- Update existing specifications to reflect the new nomenclature.

**Non-Goals:**
- Altering the underlying functionality, directory structure, or logic of the channel (how it records, transcribes, or saves).
- Migrating previously saved files on the user's device (old files named `log-*.md` can remain as they are, but new ones will use the new prefix if we change it).

## Decisions

- **Internal Naming:** We will rename the channel id and class names (e.g., `CaptainsLogChannel` -> `JournalChannel`). This keeps the codebase consistent with the UI.
- **File Prefix Naming:** We will rename the output file prefixes from `log-` to `journal-` (e.g., `journal-YYYY-MM-DD.md`).
  - *Rationale:* Consistency. If the channel is a "Journal", the files it produces should be journal files, not log files.
- **Spec naming:** The existing spec folder is `captains-log-channel`. We will leave the directory name as-is for the artifact tracking but the capability inside openspec will be considered modified. (Or we could do a rename of the capability, but for now we are tracking the modification in `captains-log-channel` delta spec).

## Risks / Trade-offs

- **Risk**: Existing users might be confused if they look for "Captain's log" and don't find it.
  - *Mitigation*: The functionality and icon (if any) will remain the same, easing the transition.
- **Risk**: Changing the filename prefix means a single day's folder might contain both `log-YYYY-MM-DD.md` and `journal-YYYY-MM-DD.md` if the user updates the app in the middle of the day.
  - *Mitigation*: This is acceptable. The new captures will just go to the new file. We do not need to build a complex migration script to merge them for a single day.
