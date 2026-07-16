# Changelog

## 1.0.1
- Single-session JSON sharing directly from each session card.
- Full-screen session debugger with timeline and raw JSON preview.
- External JSON viewer action for inspecting the exact shared file.


### Agent flow

- Replaced the legacy Accessibility-tree prompt with a compact vision-first operating contract.
- Enabled native tool schemas when the selected provider supports function calling.
- Enforced one action per visual observation.
- Added strict snapshot and marker rules, including explicit zero-marker behavior on the MobClaw host screen.
- Reduced the default temperature to `0.2` and the maximum iteration count to `60`.
- Removed literal `null` assistant messages from model history.
- Improved stuck recovery instructions and reduced repeated reminder text.

### UI

- The in-app Stop button now cancels the active agent.
- Successful result cards automatically dismiss after six seconds.
- Editing the next task clears the previous result.
- The floating completion overlay automatically hides after completion.
- Overlay actions now show visual marker numbers and labels.

### Visual observations

- Observations now expose an exact `ACTION_SNAPSHOT_ID` and an explicit allowed-marker list.
- The model is told never to invent or reuse marker IDs.
- Screenshot attachment remains limited to the newest observation.
