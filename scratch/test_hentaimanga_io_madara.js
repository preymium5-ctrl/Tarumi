const https = require('https');

function getHTML(url) {
  return new Promise((resolve) => {
    const options = {
      method: 'GET',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
      }
    };
    const req = https.request(url, options, (res) => {
      let body = '';
      res.on('data', (c) => body += c);
      res.on('end', () => {
        resolve({ status: res.statusCode, body });
      });
    });
    req.on('error', (e) => {
      resolve({ status: 500, error: e.message });
    });
    req.end();
  });
}

async function run() {
  const homepage = await getHTML('https://hentaimanga.io/');
  console.log('Homepage Status:', homepage.status);
  console.log('Contains admin-ajax:', homepage.body.includes('admin-ajax.php'));
  console.log('Contains c-tabs-item:', homepage.body.includes('c-tabs-item'));
  console.log('Contains page-item-detail:', homepage.body.includes('page-item-detail'));
  
  // Let's search for a manga to check search/catalog
  const searchPage = await getHTML('https://hentaimanga.io/?s=teacher&post_type=wp-manga');
  console.log('Search Status:', searchPage.status);
  console.log('Search Results Contains page-item-detail:', searchPage.body.includes('page-item-detail'));
}

run();
