// Save as make_manifest.js and run with: node make_manifest.js
const fs = require('fs');
const files = fs.readdirSync('.').filter(f => f.endsWith('.png'));
fs.writeFileSync('pokedex_manifest.json', JSON.stringify(files, null, 2));
console.log('Manifest written with', files.length, 'files.');