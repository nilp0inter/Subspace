## Why

The current feature is named "Captain's log channel," which is somewhat informal and specific. Renaming it to "Journal Channel" provides a clearer, more standard, and intuitive name for the feature that aligns better with broader user expectations for personal recording and logging functionality.

## What Changes

- Rename all user-facing instances of "Captain's log" or "Captain's log channel" to "Journal" or "Journal Channel".
- Update internal nomenclature (code symbols, variables, class names) to use "Journal" instead of "CaptainsLog" where practical and appropriate.
- Update documentation and specification terminology to reflect the new "Journal Channel" name.

## Capabilities

### New Capabilities

*(None)*

### Modified Capabilities

- `captains-log-channel`: Renaming the channel name and concept to "Journal Channel".

## Impact

- **UI/UX**: User-facing labels in the app will change from "Captain's log" to "Journal".
- **Codebase**: Class names, variables, strings, and resources referring to "captains log" will be renamed.
- **Documentation**: The existing `captains-log-channel` spec will be updated to reflect the new terminology.
