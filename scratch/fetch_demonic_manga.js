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

getUrl('https://demonicscans.org/manga/How-to-Send-My-Husband-to-the-Abyss')
  .then(body => {
    fs.writeFileSync('e:/Tarumi 2/Tarumi/scratch/demonic_manga.html', body);
    console.log('Saved! Length:', body.length);
  })
  .catch(err => {
    console.error('Error:', err);
  });
