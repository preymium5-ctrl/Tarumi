const https = require('https');
const fs = require('fs');

const options = {
  headers: {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
  },
  timeout: 10000,
  rejectUnauthorized: false
};

function getUrl(url) {
  return new Promise((resolve, reject) => {
    https.get(url, options, (res) => {
      let data = '';
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => {
        resolve(data);
      });
    }).on('error', reject);
  });
}

getUrl('https://demonicscans.org/manga/Spectres')
  .then(body => {
    fs.writeFileSync('e:/Tarumi 2/Tarumi/scratch/demonic_spectres.html', body);
    console.log('Saved! Length:', body.length);
    // Print lines around "genres-list" and "white-font"
    const lines = body.split('\n');
    lines.forEach((line, idx) => {
      if (line.includes('genres') || line.includes('Author') || line.includes('Status') || line.includes('white-font')) {
        console.log(`Line ${idx + 1}: ${line.trim().substring(0, 150)}`);
      }
    });
  })
  .catch(err => {
    console.error('Error:', err);
  });
