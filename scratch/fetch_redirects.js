const https = require('https');

function checkRedirect(hostname) {
  return new Promise((resolve) => {
    const options = {
      hostname: hostname,
      port: 443,
      path: '/',
      method: 'GET',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0',
      }
    };

    const req = https.request(options, (res) => {
      console.log(`[${hostname}] STATUS: ${res.statusCode}`);
      console.log(`[${hostname}] REDIRECT LOCATION: ${res.headers.location}`);
      resolve();
    });

    req.on('error', (e) => {
      console.error(`[${hostname}] ERROR: ${e.message}`);
      resolve();
    });

    req.end();
  });
}

async function run() {
  await checkRedirect('hentaimanga.me');
  await checkRedirect('adultwebtoon.com');
}

run();
