import React from 'react';

export function ReleaseNotes(){return <section className="settings-section" aria-labelledby="release-notes"><h2 id="release-notes">What’s new</h2><p>Animated Lil ICARUS · Android 1.6.11 Test</p><ul>
  <li>Replaced the flat companion with the rigged gold-and-blue Lil ICARUS model, optimized from 29 MB to 1.3 MB for mobile use.</li>
  <li>Lil ICARUS now greets, idles, listens, thinks, speaks, confirms, reacts to errors, and walks while preparing. Reduced-motion and non-WebGL devices retain the static companion fallback.</li>
  <li>Stabilized the mirrored Driving HUD by synchronizing decorative and information-layer redraws, with static presentation between real data updates.</li>
  <li>Moved edge controls into a safer glasses viewing area and removed the repeated disconnected-OBD banner.</li>
  <li>Added parked setup for live Google Maps and Waze maneuver guidance, keeping the next instruction and distance in the central safe view while driving.</li>
  <li>Vehicle now opens the phone Driving HUD and the optional bundled XREAL assistant or vehicle overlay. XREAL stays off until explicitly enabled.</li>
  <li>HUD controls verify installed support, wait for matching Android acknowledgements, and recover from failed or timed-out requests.</li>
  <li>The bundled HUD is screen-fixed, not verified tracked AR. The separate Unity companion remains a distinct route; no live readings or navigation are invented.</li>
  <li>Restored saved voice selection, speed, pitch and preview. The Deep & warm profile now applies to hands-free replies and app speech consistently.</li>
  <li>Restored wake sensitivity and local-model management, with shortcuts to Android voice and app-permission settings.</li>
  <li>A little full-body ICARUS companion is available across the app. Tap him for voice status and Talk now; move or tuck him away when you need the space.</li>
  <li>Voice now acknowledges a wake before capturing the command and keeps microphone, recognition and speech errors visible.</li>
  <li>Talk now tests command capture separately from wake detection. Voice diagnostics show microphone input, wake detections, voice stage and muted media output.</li>
  <li>Wake sensitivity now takes effect, and microphone/decoder cleanup waits for the audio worker safely.</li>
  <li>Contained mobile artwork and adjusted navigation and listening dialogs to prevent sideways page overflow.</li>
  <li>Fixed installed Android apps being incorrectly shown in Web mode with disabled voice controls.</li>
  <li>Chat can propose battery, flashlight, media volume, call, maps, app, timer, and stop-listening actions in the Android app.</li>
  <li>Each Chat action requires your confirmation. Results distinguish an Android acknowledgement from verified completion.</li>
  <li>Search web checks current information and displays source links when the configured provider supports it. Search turns cannot operate your phone.</li>
  <li>Cloud voice turns continue the selected Chat conversation and use saved Memory. Temporary mode excludes saved history and Memory.</li>
  <li>Action reports are saved with their conversation without repeating the action. Unconfirmed outcomes stay clearly marked.</li>
  <li>Connection retries reuse a completed turn instead of duplicating messages. Deleted conversations cannot be recreated by a stale retry.</li>
  <li>Failed Chat requests retain your draft. Settings navigation and excess Chat spacing have been repaired.</li>
</ul><p>Still limited: email and shopping integrations and multi-step automation are not connected. Direct offline voice commands do not enter cloud history. Failed report uploads may leave the outcome unconfirmed. Phone permissions and lock-screen restrictions still apply.</p></section>}
