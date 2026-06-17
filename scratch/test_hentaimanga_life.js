const https = require('https');

function check(domain) {
  return new Promise((resolve) => {
    const options = {
      hostname: domain,
      port: 443,
      path: '/',
      method: 'GET',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0',
      }
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
      console.log(`[${domain}] ERROR: ${e.message}`);
      resolve();
    });
    req.end();
  });
}

async function run() {
  await check('hentaimanga.life');
}
run();
