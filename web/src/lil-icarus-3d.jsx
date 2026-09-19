import React,{useEffect,useRef} from 'react';
import * as THREE from 'three';
import {GLTFLoader} from 'three/examples/jsm/loaders/GLTFLoader.js';
import {MeshoptDecoder} from 'three/examples/jsm/libs/meshopt_decoder.module.js';

const MODEL='/models/lil-icarus/lil-icarus-web.glb';
const WALK='/models/lil-icarus/walking_glb.glb';
const rest={LeftArm:[0,0,-1.16],RightArm:[0,0,1.16],LeftForeArm:[0,0,-.12],RightForeArm:[0,0,.12]};

function offsets(motion,time){
  const breathe=Math.sin(time*2.2),gesture=Math.sin(time*5.4);
  const pose={...rest,Spine02:[breathe*.018,0,0],Head:[0,breathe*.025,0]};
  if(motion==='listening')Object.assign(pose,{Head:[0,.08,.13],RightForeArm:[0,-.25,.72],RightArm:[0,.08,.92]});
  if(motion==='thinking')Object.assign(pose,{Head:[-.08,.16,-.08],RightArm:[0,.12,.72],RightForeArm:[0,-.9,1.35]});
  if(motion==='speaking')Object.assign(pose,{LeftArm:[0,.12,-.72-gesture*.08],RightArm:[0,-.12,.72+gesture*.08],LeftForeArm:[0,.2,-.7],RightForeArm:[0,-.2,.7]});
  if(motion==='greeting')Object.assign(pose,{RightArm:[0,0,.28],RightForeArm:[0,-.15,1.72+gesture*.16],Head:[0,-.08,0]});
  if(motion==='confirm')Object.assign(pose,{Head:[.12+Math.abs(gesture)*.1,0,0],LeftForeArm:[0,.1,-.48],RightForeArm:[0,-.1,.48]});
  if(motion==='error')Object.assign(pose,{Head:[0,0,-.08],LeftArm:[0,0,-.88],RightArm:[0,0,.88],LeftForeArm:[0,.3,-1.05],RightForeArm:[0,-.3,1.05]});
  return pose;
}

export function LilIcarus3D({motion='idle',onReady,onError}){
  const host=useRef(null),motionRef=useRef(motion),readyRef=useRef(onReady),errorRef=useRef(onError);
  motionRef.current=motion;readyRef.current=onReady;errorRef.current=onError;
  useEffect(()=>{
    const container=host.current;
    if(!container)return;
    if(typeof window.WebGLRenderingContext==='undefined'){errorRef.current?.(new Error('WebGL is unavailable.'));return;}
    let stopped=false,frame=0,mixer=null,walkAction=null,model=null,bones={},base={},start=performance.now();
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
        const walking=active==='preparing';
        if(walkAction)walkAction.paused=!walking;
        mixer?.update(Math.min(.05,(render.last?now-render.last:16)/1000));
        if(!walking){
          const pose=offsets(active,t);
          for(const [name,rotation] of Object.entries(pose)){
            const bone=bones[name];if(!bone)continue;
            bone.quaternion.copy(base[name]).multiply(new THREE.Quaternion().setFromEuler(new THREE.Euler(...rotation)));
          }
        }
        model.position.y=-.84+Math.sin(t*2.2)*.008;
        model.rotation.y=Math.sin(t*.55)*.045;
      }
      renderer.render(scene,camera);render.last=now;
      if(!reduced)frame=requestAnimationFrame(render);
    };
    Promise.all([loader.loadAsync(MODEL),loader.loadAsync(WALK)]).then(([character,walking])=>{
      if(stopped)return;
      model=character.scene;model.scale.setScalar(1.03);scene.add(model);
      model.traverse(object=>{if(object.isBone){bones[object.name]=object;base[object.name]=object.quaternion.clone();}if(object.isMesh){object.frustumCulled=false;}});
      mixer=new THREE.AnimationMixer(model);
      const clip=walking.animations.find(item=>item.duration>.2);
      if(clip){walkAction=mixer.clipAction(clip);walkAction.setLoop(THREE.LoopRepeat,Infinity);walkAction.play();walkAction.paused=true;}
      readyRef.current?.();start=performance.now();render(start);
    }).catch(error=>{if(!stopped)errorRef.current?.(error);});
    return()=>{
      stopped=true;cancelAnimationFrame(frame);observer?.disconnect();mixer?.stopAllAction();
      model?.traverse(object=>{if(object.geometry)object.geometry.dispose();if(object.material){const materials=Array.isArray(object.material)?object.material:[object.material];for(const material of materials){for(const value of Object.values(material))if(value?.isTexture)value.dispose();material.dispose();}}});
      renderer.dispose();renderer.domElement.remove();
    };
  },[]);
  return <span ref={host} className="companion-model" aria-hidden="true"/>;
}
