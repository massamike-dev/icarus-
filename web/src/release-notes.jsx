import React from 'react';

export function ReleaseNotes(){return <section className="settings-section" aria-labelledby="release-notes"><h2 id="release-notes">What’s new</h2><p>Assistant connections update · Android 1.6.6</p><ul>
  <li>Chat can propose battery, flashlight, media volume, call, maps, app, timer, and stop-listening actions in the Android app.</li>
  <li>Each Chat action requires your confirmation. Results distinguish an Android acknowledgement from verified completion.</li>
  <li>Search web checks current information and displays source links when the configured provider supports it. Search turns cannot operate your phone.</li>
  <li>Voice commands can fall back to conversational answers using saved Memory.</li>
  <li>Failed Chat requests retain your draft. Settings navigation and excess Chat spacing have been repaired.</li>
</ul><p>Still limited: email and shopping integrations, multi-step automation, and saved device-action history are not connected. Phone permissions, lock-screen restrictions, and device testing still apply.</p></section>}
