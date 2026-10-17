## 1. Gate

- [x] 1.1 `desktop`: `OpenSpecGates.folder(session: Session?, folders): Folder?` per D2; the KDoc of `OpenSpecGates` names the session's screen too. Verify: `OpenSpecGatesTest`:
  - a session in a marked folder → that folder;
  - a session in an unmarked folder → `null`;
  - a global session → `null`;
  - a session whose `folderId` is not in `folders` → `null`;
  - a `null` session → `null`;
  - without `openspec.changes` → `null`;
  - a dormant session and a shell session in a marked folder → the folder;
  - for a `SessionList` with a global session, sessions in a marked and an unmarked folder, and one in an unknown folder, with and without `openspec.changes`: for every session, `folder(s, list.folders) != null` exactly when `heading` holds for the group `SessionsView` puts it in.

  `./gradlew testDebugUnitTest` passes.

## 2. Interface

- [x] 2.1 `ui`: the screen and its wiring.
  - In `ConversationScreen`'s top bar, an icon button (`SpekIcons.File`, "OpenSpec for <folder name>") before the ⋮ button and outside its `actions.isNotEmpty()` guard. It is shown when 1.1 gives a folder and calls a new `onOpenSpecFolder(folderId)`.
  - `MainActivity` navigates it to `OpenSpecFolderRoute(folderId)` (D3).
  - `README.md`'s OpenSpec entry says it opens from a folder's heading, the list's menu, or a session's screen.

  Verify: `./gradlew assembleDebug`. With the emulator paired with the dev desktop (`npm run dev` in ~/git/spekterm), take light and dark screenshots of:
  - a running session in a folder with OpenSpec (icon shown);
  - a dormant one (hibernated on the desktop; icon shown);
  - a global session (no icon).

  Then `uiautomator dump` shows `content-desc="OpenSpec for <folder name>"`.
- [x] 2.2 Phone run on the Pixel paired with the desktop, recorded in `docs/conversation-findings.md` as earlier changes did, with the connection log as evidence:
  1. Open a session in a folder with OpenSpec, send a reply that makes the agent write, and tap the icon at once.
  2. While on the OpenSpec screen, the log shows the conversation's `unwatch`.
  3. Open a change, wait for the agent to finish, and go back twice. The messages written meanwhile appear once each, with no repeat, and the log shows a `watch` with `resume=true`. About 5 s later it shows `openspec op=watch stop=true`.
  4. A further agent message appears live.
  5. A global session shows no icon.
