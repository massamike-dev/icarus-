import React from 'react';
import {ChangePassword} from './password-recovery.jsx';
import {Settings as GeneralSettings} from './settings-core.jsx';
import {PremiumMembership} from './premium.jsx';
export {VoiceControls,ScreenBoundary} from './settings-core.jsx';

export function Settings(props) {
  return <><GeneralSettings {...props}/><ChangePassword onSignOut={props.onSignOut}/><PremiumMembership key={props.user.id} user={props.user}/></>;
}
