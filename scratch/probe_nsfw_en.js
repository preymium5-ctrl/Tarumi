const fs = require('fs');
const https = require('https');
const http = require('http');

const sources = JSON.parse(fs.readFileSync('e:/Tarumi 2/Tarumi/scratch/en_nsfw_sources.json', 'utf8'));
console.log('Probing', sources.length, 'sources...');

function probeDomain(source) {
  return new Promise((resolve) => {
    if (source.domain === 'unknown') {
      return resolve({ ...source, status: 'unknown_domain', error: 'No domain found' });
    }

    const domain = source.domain.replace(/^www\./, '');
    const url = `https://${source.domain}/`;
    
    const options = {
      method: 'GET',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
      },
      timeout: 5000
    };

    const client = url.startsWith('https') ? https : http;

    const req = client.request(url, options, (res) => {
      let isCloudflare = false;
      const cfHeaders = ['cf-ray', 'server', 'x-cloudflare-request-id'];
      for (const h of cfHeaders) {
        if (res.headers[h] && res.headers[h].toLowerCase().includes('cloudflare')) {
          isCloudflare = true;
        }
      }
      if (res.headers['server'] && res.headers['server'].toLowerCase() === 'cloudflare') {
        isCloudflare = true;
      }

      resolve({
        ...source,
        status: res.statusCode,
        isCloudflare,
        redirect: res.headers.location || null
      });
    });

    req.on('error', (err) => {
      resolve({
        ...source,
        status: 'error',
        error: err.message
      });
    });

    req.on('timeout', () => {
      req.destroy();
      resolve({
        ...source,
        status: 'timeout',
        error: 'Timeout'
      });
    });

    req.end();
  });
}

async function start() {
  const results = [];
  const limit = 5; // concurrency
  for (let i = 0; i < sources.length; i += limit) {
    const chunk = sources.slice(i, i + limit);
    const chunkResults = await Promise.all(chunk.map(probeDomain));
    results.push(...chunkResults);
    console.log(`Probed ${results.length}/${sources.length} sources`);
  }

  fs.writeFileSync('e:/Tarumi 2/Tarumi/scratch/probe_results.json', JSON.stringify(results, null, 2));
  console.log('Finished probing. Results saved to scratch/probe_results.json');
}

start();
