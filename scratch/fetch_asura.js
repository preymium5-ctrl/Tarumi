const https = require('https');
const fs = require('fs');

function fetchJson(url) {
  return new Promise((resolve, reject) => {
    https.get(url, { headers: { 'User-Agent': 'Mozilla/5.0' } }, (res) => {
      let data = '';
      res.on('data', (chunk) => data += chunk);
      res.on('end', () => {
        try {
          resolve(JSON.parse(data));
        } catch (e) {
          reject(new Error(`Failed to parse JSON from ${url}: ${e.message}`));
        }
      });
    }).on('error', reject);
  });
}

async function run() {
  try {
    console.log('Fetching Keiyoushi repository index...');
    const index = await fetchJson('https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json');
    console.log(`Fetched ${index.length} extension versions.`);
    
    // We want to write this to a temporary file or filter it directly.
    // Let's filter for relevant extensions.
    const keywords = ['hentai', 'porn', 'adult', 'manhwa18', 'manytoon', 'toonily', 'pururin', 'yaoihub', 'manhwa', 'owl'];
    const filtered = index.filter(ext => {
      const name = ext.name.toLowerCase();
      return keywords.some(k => name.includes(k));
    });
    
    console.log(`Filtered down to ${filtered.length} extensions.`);
    fs.writeFileSync('e:/Tarumi 2/Tarumi/scratch/keiyoushi_filtered.json', JSON.stringify(filtered, null, 2));
    console.log('Saved filtered list to scratch/keiyoushi_filtered.json');
  } catch (e) {
    console.error('Error:', e.message);
  }
}

run();
