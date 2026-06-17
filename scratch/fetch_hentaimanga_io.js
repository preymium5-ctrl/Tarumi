const https = require('https');

function check(url) {
  return new Promise((resolve) => {
    const options = {
      method: 'GET',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
        'Accept-Language': 'en-US,en;q=0.5'
      }
    };
    const req = https.request(url, options, (res) => {
      console.log(`STATUS: ${res.statusCode}`);
      let body = '';
      res.on('data', (c) => body += c);
      res.on('end', () => {
        // Find all urls / anchors
        const regex = /href="([^"]+)"/g;
        let match;
        const links = new Set();
        while ((match = regex.exec(body)) !== null) {
          links.add(match[1]);
        }
        console.log('--- ALL UNIQUE LINKS IN HOMEPAGE ---');
        for (const link of links) {
          if (link.includes('http') && !link.includes('hentaimanga.io')) {
            console.log(link);
          }
        }
        resolve();
      });
    });
    req.on('error', (e) => {
      console.log(`ERROR: ${e.message}`);
      resolve();
    });
    req.end();
  });
}

check('https://hentaimanga.io/');
