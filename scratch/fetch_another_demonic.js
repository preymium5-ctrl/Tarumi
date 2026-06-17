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
    }).on('error', reject);
  });
}

getUrl('https://demonicscans.org/chaptered.php?manga=11640&chapter=37')
  .then(res => {
    const imgRegex = /<img[^>]+class="imgholder"[^>]*>/g;
    let match;
    while ((match = imgRegex.exec(res.body)) !== null) {
      console.log(match[0]);
    }
  })
  .catch(err => {
    console.error('Error:', err);
  });
