import React,{useEffect,useRef} from 'react';
import * as THREE from 'three';
import {GLTFLoader} from 'three/examples/jsm/loaders/GLTFLoader.js';
import {MeshoptDecoder} from 'three/examples/jsm/libs/meshopt_decoder.module.js';
import {rigTargetForBoneName} from './lil-icarus-rig.js';

const MODEL='/models/lil-icarus/lil-icarus-web.glb';
const WALK='/models/lil-icarus/walking_glb.glb';
const rest={LeftArm:[0,0,-1.16],RightArm:[0,0,1.16],LeftForeArm:[0,0,-.12],RightForeArm:[0,0,.12]};

function offsets(motion,time){
  const breathe=Math.sin(time*2.2),gesture=Math.sin(time*5.4);
  const pose={...rest,Spine02:[breathe*.018,0,0],Head:[0,breathe*.025,0]};
  if(motion==='preparing')Object.assign(pose,{Head:[-.03,0,.03],LeftArm:[0,.04,-1.02],RightArm:[0,-.04,1.02],LeftForeArm:[0,.12,-.3],RightForeArm:[0,-.12,.3]});
  if(motion==='listening')Object.assign(pose,{Head:[0,.08,.13],RightForeArm:[0,-.25,.72],RightArm:[0,.08,.92]});
  if(motion==='thinking')Object.assign(pose,{Head:[-.08,.16,-.08],RightArm:[0,.12,.72],RightForeArm:[0,-.9,1.35]});
  if(motion==='speaking')Object.assign(pose,{LeftArm:[0,.12,-.72-gesture*.08],RightArm:[0,-.12,.72+gesture*.08],LeftForeArm:[0,.2,-.7],RightForeArm:[0,-.2,.7]});
  if(motion==='greeting')Object.assign(pose,{RightArm:[0,0,.28],RightForeArm:[0,-.15,1.72+gesture*.16],Head:[0,-.08,0]});
  if(motion==='confirm')Object.assign(pose,{Head:[.12+Math.abs(gesture)*.1,0,0],LeftForeArm:[0,.1,-.48],RightForeArm:[0,-.1,.48]});
  if(motion==='error')Object.assign(pose,{Head:[0,0,-.08],LeftArm:[0,0,-.88],RightArm:[0,0,.88],LeftForeArm:[0,.3,-1.05],RightForeArm:[0,-.3,1.05]});
  return pose;
}

function disposeObject(root){
  root?.traverse?.(object=>{
    if(object.geometry)object.geometry.dispose();
    if(object.material){
      const materials=Array.isArray(object.material)?object.material:[object.material];
      for(const material of materials){
        for(const value of Object.values(material))if(value?.isTexture)value.dispose();
        material.dispose();
      }
    }
  });
}

export function LilIcarus3D({motion='idle',onReady,onError}){
  const host=useRef(null),motionRef=useRef(motion),readyRef=useRef(onReady),errorRef=useRef(onError);
  motionRef.current=motion;readyRef.current=onReady;errorRef.current=onError;
  useEffect(()=>{
    const container=host.current;
    if(!container)return;
    if(typeof window.WebGLRenderingContext==='undefined'){errorRef.current?.(new Error('WebGL is unavailable.'));return;}
    let stopped=false,frame=0,mixer=null,walkAction=null,walkPlaying=false,model=null,bones={},base={},start=performance.now();
    const reduced=window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const scene=new THREE.Scene();
    const camera=new THREE.PerspectiveCamera(26,1,.1,20);
    camera.position.set(0,.88,4.35);camera.lookAt(0,.82,0);
    const renderer=new THREE.WebGLRenderer({alpha:true,antialias:true,powerPreference:'low-power'});
    renderer.setPixelRatio(Math.min(devicePixelRatio,1.5));renderer.outputColorSpace=THREE.SRGBColorSpace;
    renderer.toneMapping=THREE.ACESFilmicToneMapping;renderer.toneMappingExposure=1.1;
    renderer.domElement.className='companion-canvas';renderer.domElement.setAttribute('aria-hidden','true');
    container.appendChild(renderer.domElement);
    scene.add(new THREE.HemisphereLight(0xc9edff,0x17202c,2.25));
    const key=new THREE.DirectionalLight(0xffd28a,3.2);key.position.set(2.5,3.5,4);scene.add(key);
    const rim=new THREE.DirectionalLight(0x45bfff,2.4);rim.position.set(-3,2,-2);scene.add(rim);
    const loader=new GLTFLoader();loader.setMeshoptDecoder(MeshoptDecoder);
    const resize=()=>{const {width,height}=container.getBoundingClientRect();renderer.setSize(Math.max(1,width),Math.max(1,height),false);camera.aspect=width/Math.max(1,height);camera.updateProjectionMatrix();};
    const observer=typeof ResizeObserver==='undefined'?null:new ResizeObserver(resize);observer?.observe(container);resize();
    const render=now=>{
      if(stopped)return;
      const t=(now-start)/1000,active=t<2.4?'greeting':motionRef.current;
      if(model&&!reduced){
        const walking=active==='preparing'&&Boolean(walkAction);
        if(walkAction&&walking&&!walkPlaying){walkAction.reset().play();walkPlaying=true;}
        else if(walkAction&&!walking&&walkPlaying){walkAction.stop();walkPlaying=false;}
        mixer?.update(Math.min(.05,(render.last?now-render.last:16)/1000));
        if(!walking){
          const pose=offsets(active,t);
          for(const [name,rotation] of Object.entries(pose)){
            const bone=bones[name];if(!bone)continue;
            bone.quaternion.copy(base[name]).multiply(new THREE.Quaternion().setFromEuler(new THREE.Euler(...rotation)));
          }
        }
        const bounce=active==='speaking'?.014:active==='confirm'?.012:.008;
        const sway=active==='thinking'?.075:active==='speaking'?.045:.03;
        model.position.y=-.84+Math.sin(t*2.2)*bounce;
        model.rotation.y=Math.sin(t*(active==='thinking'?1.65:.55))*sway;
        model.rotation.z=active==='error'?-.035:active==='listening'?Math.sin(t*1.25)*.012:0;
      }
      renderer.render(scene,camera);render.last=now;
      if(!reduced)frame=requestAnimationFrame(render);
    };
    loader.loadAsync(MODEL).then(character=>{
      if(stopped){disposeObject(character.scene);return;}
      model=character.scene;model.scale.setScalar(1.03);scene.add(model);
      model.traverse(object=>{
        if(object.isBone){
          const target=rigTargetForBoneName(object.name);
          if(target&&!bones[target]){bones[target]=object;base[target]=object.quaternion.clone();}
        }
        if(object.isMesh)object.frustumCulled=false;
      });
      mixer=new THREE.AnimationMixer(model);
      readyRef.current?.({rigBones:Object.keys(bones),walkAnimation:false});
      start=performance.now();render(start);
      loader.loadAsync(WALK).then(walking=>{
        if(stopped){disposeObject(walking.scene);return;}
        const clip=walking.animations.find(item=>item.duration>.2);
        if(clip&&mixer){
          walkAction=mixer.clipAction(clip);walkAction.setLoop(THREE.LoopRepeat,Infinity);
        }
        disposeObject(walking.scene);
      }).catch(()=>{/* Walking is an enhancement; the rigged fallback poses keep Lil ICARUS alive. */});
    }).catch(error=>{if(!stopped)errorRef.current?.(error);});
    return()=>{
      stopped=true;cancelAnimationFrame(frame);observer?.disconnect();mixer?.stopAllAction();
      disposeObject(model);renderer.dispose();renderer.domElement.remove();
    };
  },[]);
  return <span ref={host} className="companion-model" aria-hidden="true"/>;
}
