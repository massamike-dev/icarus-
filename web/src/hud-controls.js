// Never confuse a packaged overlay with a connected or tracked glasses display.
export function validHudStatus(data) {
  return data?.provider==='xreal'&&typeof data.enabled==='boolean'&&typeof data.runtimeAvailable==='boolean';
}

export function hudResultMessage(action,args,data) {
  if(action==='open_driving_hud'&&data?.opened===true)return 'Android accepted the phone HUD launch. Live vehicle readings require a connected OBD adapter.';
  if(action==='open_xreal_hud'&&data?.launched===true&&data?.runtimeAvailable===true&&data?.mode===args.mode)return 'Android accepted the bundled HUD launch. Check your display; glasses connection and tracking are not verified.';
  if(action==='close_xreal_hud'&&data?.closeRequested===true)return 'Android received the HUD close request.';
  if(action==='close_xreal_hud'&&data?.alreadyClosed===true)return 'The bundled HUD is already closed.';
  if(action==='meta_integration_set'&&data?.provider==='xreal'&&data?.enabled===args.enabled)return `XREAL ${args.enabled?'enabled':'disabled'} on this device.`;
  throw Error('Android did not confirm the expected HUD result. Check your display, then refresh HUD status.');
}
