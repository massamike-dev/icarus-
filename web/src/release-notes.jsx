import React from 'react';

export function ReleaseNotes(){return <section className="settings-section" aria-labelledby="release-notes"><h2 id="release-notes">What’s new</h2><p>Assistant connections update · Android 1.6.6</p><ul>
  <li>Fixed installed Android apps being incorrectly shown in Web mode with disabled voice controls.</li>
  <li>Chat can propose battery, flashlight, media volume, call, maps, app, timer, and stop-listening actions in the Android app.</li>
  <li>Each Chat action requires your confirmation. Results distinguish an Android acknowledgement from verified completion.</li>
  <li>Search web checks current information and displays source links when the configured provider supports it. Search turns cannot operate your phone.</li>
  <li>Cloud voice turns continue the selected Chat conversation and use saved Memory. Temporary mode excludes saved history and Memory.</li>
  <li>Action reports are saved with their conversation without repeating the action. Unconfirmed outcomes stay clearly marked.</li>
  <li>Connection retries reuse a completed turn instead of duplicating messages. Deleted conversations cannot be recreated by a stale retry.</li>
  <li>Failed Chat requests retain your draft. Settings navigation and excess Chat spacing have been repaired.</li>
</ul><p>Still limited: email and shopping integrations and multi-step automation are not connected. Direct offline voice commands do not enter cloud history. Failed report uploads may leave the outcome unconfirmed. Phone permissions and lock-screen restrictions still apply.</p></section>}
