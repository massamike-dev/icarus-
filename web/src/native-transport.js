// Android injects ICARUS_NATIVE_CHANNEL into the trusted WebView. Keep the
// legacy alias only for older installed builds.
export function getNativeTransport(host=globalThis.window||globalThis) {
  const channel=host?.ICARUS_NATIVE_CHANNEL;
  if(typeof channel?.postMessage==='function')return channel;
  const legacy=host?.IcarusNative;
  return typeof legacy?.postMessage==='function'?legacy:null;
}
