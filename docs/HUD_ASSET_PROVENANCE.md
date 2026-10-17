# Holographic pickup artwork

The HUD uses `native/android/xreal/src/main/res/drawable-nodpi/icarus_hud_pickup.png` as a decorative, illustrative vehicle. It is not a camera frame, a scan of the connected vehicle, or a model-based diagnostic assessment.

The asset was created with the built-in OpenAI image-generation tool using the user's original ICARUS windshield concept (`5345(3).png`, re-uploaded as `6291.png`) as the visual reference. A second edit removed baked-in warm engine/headlamp/tail-lamp accents so the neutral base can be used when no live vehicle data exists. No paid external asset service, API key or new account was used.

Verified final file:
- 1774 × 887 pixels, RGBA PNG
- 2,110,918 bytes
- Alpha range 0–255; 670,919 fully transparent pixels
- SHA-256: `0f95b942440203d0b77ab0188d8ea60fe977d5e6ef250a1f25f8aef3364fcda9`

The original generated file is copied unchanged into the app. Android decodes a half-resolution copy once per HUD view, with resource density scaling disabled. It is drawn into the lower telemetry band with its aspect ratio preserved. A code-native wireframe remains as the graceful decoding fallback. See `DRIVING_GRAPHICS_RESTORATION.md` for runtime verification limits.

## First generation prompt

Use case: background-extraction / precise-object-edit. Asset type: high-resolution transparent PNG illustration for the ICARUS Android driving HUD. From the supplied ICARUS windshield concept, extract and faithfully recreate ONLY the luminous blue holographic pickup truck at lower center, as a dedicated clean UI asset. Match that truck's realistic three-quarter view, nose facing right, chassis proportions, intricate fine cyan-blue wireframe mesh, transparent technical cutaway layers, carefully detailed rims, grille, cabin, bed and undercarriage. Render at premium cinematic HUD quality, crisp thin light trails with subtle electric-blue volumetric bloom, much richer and more three-dimensional than a simple line icon. Keep the full truck entirely visible in a wide landscape asset with generous but not excessive transparent margin. Mostly cool cyan and blue; tiny restrained warm-gold accent on the engine may be included as decorative illustration, not a diagnostic reading. No text, numbers, gauges, checkmarks, badges, road, background, floor, pedestrians, steering wheel, UI panels, or route arrows. Genuine transparent alpha background, not a black rectangle or checkerboard baked into pixels. This is an illustrative vehicle asset, not a live scan or identification of any real owner's vehicle. Preserve the reference style and direction; do not create an entire HUD mockup.

## Final neutral-blue edit prompt

Edit this transparent holographic pickup asset. Change only the warm yellow/gold/orange/red glows in the front engine/headlight region and rear tail light to the same cool luminous cyan/electric-blue palette as the rest of the truck. This neutral blue-only base will be shown even when no vehicle telemetry exists, so it must not look like a highlighted fault or hot engine. Preserve every detail of the realistic three-quarter pickup geometry, nose-facing-right orientation, intricate wireframe/cutaway mesh, full vehicle framing, fine wheel/rim detail, translucency and genuine transparent alpha background. No text, gauges, floor, new background, badges or extra objects. Keep the asset equally high fidelity and crisp; do not simplify the mesh. Only change the warm accent colors.
