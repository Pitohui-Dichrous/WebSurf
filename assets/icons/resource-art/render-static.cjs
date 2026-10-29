// Mechanical format conversion only: preserve the supplied SVG geometry and colors.
// Requires sharp 0.35.5 (libvips 8.18.7, librsvg 2.63.2 for the recorded output).
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const sharp = require('sharp');

const root = path.resolve(__dirname, '../../..');
const destination = path.join(root, 'app/src/main/res/drawable-nodpi');
const mappings = {
  'unknown-file.svg': 'ws_resource_unknown.png',
  'documents-folder.svg': 'ws_resource_document.png',
  'audio-eighth-note.svg': 'ws_resource_audio.png',
  'loading-wheel.svg': 'ws_resource_loading_static.png',
};

(async () => {
  fs.mkdirSync(destination, { recursive: true });
  for (const [source, output] of Object.entries(mappings)) {
    const input = path.join(__dirname, source);
    const target = path.join(destination, output);
    await sharp(input, { density: 72 })
      .resize(512, 512, { fit: 'contain', kernel: sharp.kernel.lanczos3 })
      .png({ compressionLevel: 9, adaptiveFiltering: false, palette: false })
      .toFile(target);
    console.log(`${output} ${crypto.createHash('sha256').update(fs.readFileSync(target)).digest('hex')}`);
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
