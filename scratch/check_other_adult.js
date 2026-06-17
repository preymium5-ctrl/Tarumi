const https = require('https');

const candidates = [
  'https://manga18.me/',
  'https://mangaforfree.net/',
  'https://manhwa-raw.com/',
  'https://manhwax.top/',
  'https://manhwalover.org/',
  'https://manga18.app/',
  'https://bestporncomix.com/',
  'https://manhwa18.cc/',
  'https://manhwa18.net/'
];

function check(url) {
  return new Promise((resolve) => {
    const options = {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
      },
      timeout: 8000,
      rejectUnauthorized: false
    };
    const req = https.get(url, options, (res) => {
      console.log(`Candidate: ${url} -> Status: ${res.statusCode}`);
      let body = '';
      res.on('data', chunk => body += chunk);
      res.on('end', () => {
        const titleMatch = body.match(/<title>([^<]+)<\/title>/i);
        console.log(`Title: ${titleMatch ? titleMatch[1].trim() : 'N/A'}`);
        console.log(`Location: ${res.headers.location || 'N/A'}`);
        console.log(`Length: ${body.length}`);
        console.log('---------------------------');
        resolve();
      });
    });
    req.on('error', err => {
      console.log(`Candidate: ${url} -> Error: ${err.message}`);
      resolve();
    });
    req.on('timeout', () => {
      req.destroy();
      console.log(`Candidate: ${url} -> Timeout`);
      resolve();
    });
  });
}

async function run() {
  for (const c of candidates) {
    await check(c);
  }
}
run();
