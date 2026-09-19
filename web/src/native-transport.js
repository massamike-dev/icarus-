// MainActivity injects this channel into the trusted Android WebView. Keep the
// legacy alias as a fallback for older hosts, never as the primary connection.
export function getNativeTransport(host=globalThis.window||globalThis) {
  const channel=host?.ICARUS_NATIVE_CHANNEL;
  if(typeof channel?.postMessage==='function')return channel;
  const legacy=host?.IcarusNative;
  return typeof legacy?.postMessage==='function'?legacy:null;
}
