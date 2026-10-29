# Resource artwork

The six original files in this directory were supplied by the user for WebSurf's resource list. They are preserved byte for byte. The SVG files include an embedded Phosphor Icons MIT attribution; it is reproduced in `Phosphor-Icons-MIT.txt` and in the application licenses.

| Source | Resource | Use |
| --- | --- | --- |
| `unknown-file.svg` | `ws_resource_unknown.png` | Media without an available preview |
| `documents-folder.svg` | `ws_resource_document.png` | Other files |
| `audio-eighth-note.svg` | `ws_resource_audio.png` | Audio files |
| `loading-wheel.svg` | `ws_resource_loading_static.png` | Static loading fallback |
| `loading-wheel-animated.gif` | `res/raw/ws_resource_loading_animated.gif` | Native animated loading indicator |
| `loading-wheel-animated.svg` | Original retained | Animated SVG source reference |

Android does not display SVG/CSS animation directly in an ImageView. The supplied GIF is therefore copied unchanged to `app/src/main/res/raw/ws_resource_loading_animated.gif` for native byte decoding and animation. It contains eight 512 × 512 frames, 130 ms per frame, and an infinite loop.

The four static Android resources are mechanical 512 × 512 PNG renders. Their SVG shear matrices, nested clipping and shadows are preserved through librsvg instead of being approximated as Android VectorDrawable paths. There is no redraw, recoloring, tint, crop, added margin or design change. The 512 px resources exceed the resource-list display size while limiting decoded bitmap memory.

`provenance.json` records the original and output hashes, renderer versions and resource mappings. Reproduction requires Node.js and Sharp 0.35.5 with the recorded libvips/librsvg versions; run `node assets/icons/resource-art/render-static.cjs` from the project root with Sharp available to Node. The converter is a development tool and adds no app runtime dependency. Runtime scaling uses the complete square artwork.
