const https = require('https');
const fs = require('fs');

const options = {
  headers: {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.9',
  },
  timeout: 10000,
  rejectUnauthorized: false
};

function getUrl(url) {
  return new Promise((resolve, reject) => {
    console.log('Fetching:', url);
    const req = https.get(url, options, (res) => {
      console.log('Status:', res.statusCode);
      console.log('Headers:', res.headers);
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        let redirectUrl = res.headers.location;
        if (!redirectUrl.startsWith('http')) {
          redirectUrl = new URL(redirectUrl, url).href;
        }
        resolve(getUrl(redirectUrl));
        return;
      }
      let data = '';
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => {
        resolve({ statusCode: res.statusCode, body: data });
      });
    });
    req.on('error', reject);
    req.on('timeout', () => {
      req.destroy();
      reject(new Error('Timeout'));
    });
  });
}

getUrl('https://demonicscans.org/chaptered.php?manga=13181&chapter=61')
  .then(res => {
    fs.writeFileSync('e:/Tarumi 2/Tarumi/scratch/demonic_chapter.html', res.body);
    console.log('Saved! Length:', res.body.length);
  })
  .catch(err => {
    console.error('Error:', err);
  });
