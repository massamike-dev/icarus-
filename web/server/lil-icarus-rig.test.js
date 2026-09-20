import test from 'node:test';
import assert from 'node:assert/strict';
import {normalizeRigName,rigTargetForBoneName} from '../src/lil-icarus-rig.js';

test('normalizes common rig namespaces and separators',()=>{
  assert.equal(normalizeRigName('mixamorig:LeftForeArm'),'leftforearm');
  assert.equal(normalizeRigName('Armature|Spine_02'),'spine02');
});

test('maps Mixamo and Blender-style bones to ICARUS motion targets',()=>{
  assert.equal(rigTargetForBoneName('mixamorigLeftArm'),'LeftArm');
  assert.equal(rigTargetForBoneName('mixamorig:RightForeArm'),'RightForeArm');
  assert.equal(rigTargetForBoneName('upper_arm.L'),'LeftArm');
  assert.equal(rigTargetForBoneName('lower_arm.R'),'RightForeArm');
  assert.equal(rigTargetForBoneName('J_Bip_C_Chest'),'Spine02');
  assert.equal(rigTargetForBoneName('Head'),'Head');
  assert.equal(rigTargetForBoneName('HeadTop_End'),null);
});
