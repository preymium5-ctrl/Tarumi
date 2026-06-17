const https = require('https');

function checkSite(hostname, path) {
  return new Promise((resolve) => {
    const options = {
      hostname: hostname,
      port: 443,
      path: path,
      method: 'GET',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
        'Accept-Language': 'en-US,en;q=0.5',
        'Connection': 'keep-alive',
      }
    };

    const req = https.request(options, (res) => {
      console.log(`[${hostname}] STATUS: ${res.statusCode}`);
      let body = '';
      res.on('data', (chunk) => {
        body += chunk;
      });

      res.on('end', () => {
        console.log(`[${hostname}] BODY LENGTH: ${body.length}`);
        const hasTabs = body.includes('c-tabs-item') || body.includes('page-item-detail');
        console.log(`[${hostname}] CONTAINS MANGA ELEMENTS: ${hasTabs}`);
        resolve();
      });
    });

    req.on('error', (e) => {
      console.error(`[${hostname}] ERROR: ${e.message}`);
      resolve();
    });

    req.end();
  });
}

async function run() {
  await checkSite('hentaimanga.me', '/?s=&post_type=wp-manga');
  await checkSite('adultwebtoon.com', '/?s=&post_type=wp-manga');
}

run();
