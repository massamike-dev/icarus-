import React, {useEffect, useRef, useState} from 'react';
import {checkPrivateApk, downloadPrivateApk} from './private-download.js';

export function PrivateDownload() {
  const [state, setState] = useState({phase:'checking', message:'Checking the private test installer…'});
  const request = useRef(null), artifact = useRef(null), expiry = useRef(null), active = useRef(false);
  const clearArtifact = () => { clearTimeout(expiry.current); if (artifact.current) URL.revokeObjectURL(artifact.current); artifact.current = null; };
  const run = async download => {
    if (request.current) return;
    clearArtifact();
    const controller = new AbortController(); request.current = controller;
    setState({phase:download?'downloading':'checking', message:download?'Receiving the complete installer…':'Checking the private test installer…'});
    const update = value => { if (active.current && request.current === controller) setState(value); };
    try {
      const token = localStorage.getItem('icarus_token');
      if (!download) {
        const metadata = await checkPrivateApk({token, signal:controller.signal});
        update({phase:'ready', metadata, message:'Private installer available. The complete file will be checked before you save it.'});
      } else {
        const result = await downloadPrivateApk({token, signal:controller.signal,
          onProgress:(received,total) => update({phase:'downloading', received, total, message:'Receiving the complete installer…'}),
          onVerify:() => update({phase:'downloading', message:'Checking the full installer’s SHA-256 checksum…'}),
        });
        if (!active.current || request.current !== controller || controller.signal.aborted) return;
        const url = URL.createObjectURL(result.blob); artifact.current = url;
        update({phase:'verified', ...result, blob:undefined, url, message:'The complete installer passed its size and SHA-256 checks. Tap Save verified APK to download it.'});
        expiry.current = setTimeout(() => {
          clearArtifact();
          if (active.current) setState({phase:'ready', message:'The prepared download expired. Prepare the installer again to save it.'});
        }, 300000);
      }
    } catch (error) { update({phase:error.kind === 'public'?'public':'error', kind:error.kind, message:error.message || 'The installer could not be prepared. Try again.'}); }
    finally { if (request.current === controller) request.current = null; }
  };
  useEffect(() => {
    active.current = true; document.title = 'Private test download | ICARUS'; run(false);
    return () => { active.current = false; request.current?.abort(); request.current = null; clearArtifact(); };
  }, []);
  const busy = state.phase === 'checking' || state.phase === 'downloading';
  return <main className="workspace settings-workspace settings-preferences">
    <p className="eyebrow">ICARUS TEST · PRIVATE ACCESS</p><h1>Your test installer</h1>
    <section className="settings-section" aria-labelledby="private-installer-title">
      <h2 id="private-installer-title">Keep your ICARUS Test app</h2>
      <p>This installer is for the separate ICARUS Test app. Keep your existing app installed; Android will check whether it can update it.</p>
      <p className={`settings-status${state.phase === 'error'?' settings-error':''}`} role="status" aria-live="polite">{state.message}</p>
      {state.total && <progress className="settings-model-progress" value={state.received} max={state.total} aria-label="Installer download progress"/>}
      {state.metadata && <p>{state.phase === 'verified'?'Verified':'Expected'} file size: {(state.metadata.size / 1048576).toFixed(2)} MiB. Build reference: {state.metadata.commit.slice(0, 8)}.</p>}
      <div className="settings-actions" aria-busy={busy}>
        {state.phase !== 'public' && state.kind !== 'signin' && (state.phase === 'verified' ?
          <a className="primary" href={state.url} download={state.filename} onClick={() => setState(value => ({...value, message:'Download requested. Open ICARUS-Test.apk in Downloads. If Android offers Update, select it; do not uninstall your existing test app.'}))}>Save verified APK</a> :
          <button type="button" className="primary" disabled={busy} onClick={() => run(true)}>Download ICARUS Test</button>)}
        {state.phase !== 'public' && state.kind !== 'signin' && <button type="button" className="secondary" disabled={!busy} onClick={() => request.current?.abort()}>Cancel download</button>}
        {state.kind === 'signin' && <button type="button" className="secondary" onClick={() => { localStorage.removeItem('icarus_token'); window.location.reload(); }}>Sign in again</button>}
      </div>
      <p>No installer is saved until all bytes match the private build’s checksum. This page does not install the app or change your saved settings.</p>
      <div className="settings-links"><a href="/">Return to ICARUS</a></div>
    </section>
  </main>;
}
