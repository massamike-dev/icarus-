import React from 'react';
import {Settings as GeneralSettings} from './settings-core.jsx';
import {Integrations} from './integrations.jsx';
import {PremiumMembership} from './premium.jsx';
import {IntelligenceMode} from './intelligence-mode.jsx';
import {isLocalMode} from './local-core.js';
export {VoiceControls,ScreenBoundary} from './settings-core.jsx';

export function Settings(props) {
  return <><GeneralSettings {...props}/><section className="workspace settings-workspace"><IntelligenceMode/></section>{!isLocalMode()&&<><Integrations key={props.user.id} user={props.user} callback={props.integrationCallback}/><PremiumMembership key={props.user.id} user={props.user}/></>}</>;
}
