const https = require('https');

const tlds = [
  'com', 'net', 'org', 'me', 'biz', 'info', 'xyz', 'top', 'site', 'online', 'club', 'cc', 'us', 'vip', 'to', 'co', 'tv', 'io',
  'app', 'blog', 'icu', 'ink', 'link', 'live', 'mobi', 'one', 'pro', 'pub', 'space', 'tech', 'today', 'website', 'work',
  'best', 'fit', 'fun', 'host', 'ltd', 'press', 'run', 'shop', 'store', 'stream', 'studio', 'style', 'systems', 'trade',
  'uno', 'web', 'wiki', 'win', 'world', 'zone', 'asia', 'eu', 'uk', 'ru', 'fr', 'de', 'nl', 'se', 'it', 'es', 'ch', 'pl'
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
      timeout: 2500
    };
    const req = https.request(options, (res) => {
      let body = '';
      res.on('data', (c) => body += c);
      res.on('end', () => {
        const isMadara = body.includes('wp-manga') || body.includes('c-tabs-item') || body.includes('page-item-detail');
        if (isMadara || res.statusCode === 200) {
          console.log(`[FOUND] ${domain} -> STATUS: ${res.statusCode} | isMadara: ${isMadara} | LENGTH: ${body.length}`);
        }
        resolve();
      });
    });
    req.on('error', (e) => {
      resolve();
    });
    req.end();
  });
}

async function run() {
  console.log('Probing all TLDs for Madara HentaiManga...');
  const promises = tlds.map(tld => check(`hentaimanga.${tld}`));
  await Promise.all(promises);
  console.log('Finished probing.');
}
run();
