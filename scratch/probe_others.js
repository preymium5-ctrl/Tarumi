const https = require('https');

const domains = [
  'hentaiwebtoon.io',
  'hentaiwebtoon.me',
  'hentaiwebtoon.co',
  'hentaiwebtoon.net',
  'adultwebtoon.io',
  'adultwebtoon.co',
  'adultwebtoon.net',
  'adultwebtoon.xyz',
  'adultwebtoon.top',
  'adultwebtoons.com',
  'adultwebtoons.net',
  'adultwebtoons.org',
  'adultwebtoons.io',
  'gatemanga.co',
  'gatemanga.net',
  'gatemanga.io',
  'gatemanga.xyz'
];

function check(domain) {
  return new Promise((resolve) => {
    const options = {
      hostname: domain,
      port: 443,
      path: '/',
      method: 'GET',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0',
      },
      timeout: 3000
    };
    const req = https.request(options, (res) => {
      console.log(`[${domain}] STATUS: ${res.statusCode} | LOCATION: ${res.headers.location || 'N/A'}`);
      let body = '';
      res.on('data', (c) => body += c);
      res.on('end', () => {
        const isMadara = body.includes('wp-manga') || body.includes('c-tabs-item') || body.includes('page-item-detail');
        console.log(`[${domain}] BODY LENGTH: ${body.length} | isMadara: ${isMadara}`);
        resolve();
      });
    });
    req.on('error', (e) => {
      // Don't log dns/connection errors to keep output clean unless it's interesting
      if (!e.message.includes('ENOTFOUND') && !e.message.includes('ECONNREFUSED')) {
        console.log(`[${domain}] ERROR: ${e.message}`);
      }
      resolve();
    });
    req.end();
  });
}

async function run() {
  for (const domain of domains) {
    await check(domain);
  }
}
run();
