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

getUrl('https://demonicscans.org/lastupdates.php?list=1')
  .then(body => {
    fs.writeFileSync('e:/Tarumi 2/Tarumi/scratch/demonic_list.html', body);
    console.log('Saved! Length:', body.length);
    // Find all links to manga or updates elements
    const linkRegex = /<a href="([^"]+)"/g;
    let match;
    const links = new Set();
    while ((match = linkRegex.exec(body)) !== null) {
      links.add(match[1]);
    }
    console.log('Links found:', Array.from(links).slice(0, 50));
  })
  .catch(err => {
    console.error('Error:', err);
  });
