const https = require('https');

const tlds = ['com', 'net', 'org', 'me', 'biz', 'info', 'xyz', 'top', 'site', 'online', 'club', 'cc', 'us', 'vip', 'to', 'co', 'tv', 'io'];

function checkDomain(domain) {
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
      if (res.statusCode === 301 || res.statusCode === 302) {
        resolve({ domain, status: 'REDIRECT', location: res.headers.location, code: res.statusCode });
        return;
      }

      let body = '';
      res.on('data', (chunk) => { body += chunk; });
      res.on('end', () => {
        const isMadara = body.includes('wp-manga') || body.includes('c-tabs-item') || body.includes('page-item-detail');
        resolve({ domain, status: 'OK', code: res.statusCode, isMadara, length: body.length });
      });
    });

    req.on('error', (e) => {
      resolve({ domain, status: 'HTTP_ERROR', error: e.message });
    });

    req.on('timeout', () => {
      req.destroy();
      resolve({ domain, status: 'TIMEOUT' });
    });

    req.end();
  });
}

async function run() {
  console.log('--- Probing HentaiManga Variations ---');
  for (const tld of tlds) {
    const domain = `hentaimanga.${tld}`;
    const result = await checkDomain(domain);
    if (result.status !== 'HTTP_ERROR' && result.status !== 'TIMEOUT') {
      console.log(JSON.stringify(result));
    }
  }

  console.log('\n--- Probing AdultWebtoon Variations ---');
  for (const tld of tlds) {
    const domain = `adultwebtoon.${tld}`;
    const result = await checkDomain(domain);
    if (result.status !== 'HTTP_ERROR' && result.status !== 'TIMEOUT') {
      console.log(JSON.stringify(result));
    }
  }
}

run();
