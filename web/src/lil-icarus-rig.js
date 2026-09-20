export const RIG_TARGETS=Object.freeze({
  LeftArm:['leftarm','leftupperarm','lupperarm','upperarml'],
  RightArm:['rightarm','rightupperarm','rupperarm','upperarmr'],
  LeftForeArm:['leftforearm','leftlowerarm','lforearm','llowerarm','forearml','lowerarml'],
  RightForeArm:['rightforearm','rightlowerarm','rforearm','rlowerarm','forearmr','lowerarmr'],
  Spine02:['spine02','spine2','spine03','spine3','upperchest','chest'],
  Head:['head'],
});

export function normalizeRigName(name=''){
  return String(name)
    .replace(/mixamorig/gi,'')
    .replace(/armature/gi,'')
    .replace(/[^a-z0-9]/gi,'')
    .toLowerCase();
}

export function rigTargetForBoneName(name=''){
  const normalized=normalizeRigName(name);
  if(!normalized)return null;
  for(const [target,aliases] of Object.entries(RIG_TARGETS)){
    if(aliases.some(alias=>normalized===alias||normalized.endsWith(alias)))return target;
  }
  return null;
}
