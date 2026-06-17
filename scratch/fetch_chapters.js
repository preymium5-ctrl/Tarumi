const https = require('https');
const fs = require('fs');

const options = {
  hostname: 'mangadistrict.com',
  port: 443,
  path: '/series/not-sober-uncensored/ajax/chapters/',
  method: 'POST',
  headers: {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Content-Length': 0
  }
};

const req = https.request(options, (res) => {
  console.log('Status Code:', res.statusCode);
  let data = '';
  res.on('data', (chunk) => {
    data += chunk;
  });
  res.on('end', () => {
    fs.writeFileSync('e:/Tarumi 2/Tarumi/scratch/chapters_response.html', data);
    console.log('Response length:', data.length);
    console.log('Sample response:', data.substring(0, 1000));
  });
});

req.on('error', (e) => {
  console.error(e);
});

req.end();
