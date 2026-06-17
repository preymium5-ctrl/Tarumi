const https = require('https');
const fs = require('fs');

function fetch(url, filepath) {
  console.log('Fetching:', url);
  const options = {
    headers: {
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
    }
  };

  https.get(url, options, (res) => {
    if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
      let redirectUrl = res.headers.location;
      if (!redirectUrl.startsWith('http')) {
        const u = new URL(url);
        redirectUrl = u.protocol + '//' + u.host + redirectUrl;
      }
      fetch(redirectUrl, filepath);
      return;
    }

    let data = '';
    res.on('data', (chunk) => {
      data += chunk;
    });
    res.on('end', () => {
      fs.writeFileSync(filepath, data);
      console.log('Finished downloading, status code:', res.statusCode);
      
      // Print some lines to see structure
      const lines = data.split('\n');
      console.log('Total lines:', lines.length);
      const items = lines.filter(l => l.includes('page-item-detail') || l.includes('c-tabs-item') || l.includes('post-title'));
      console.log('Found matching lines:', items.length);
      items.slice(0, 10).forEach(i => console.log('MATCH:', i.trim()));
    });
  }).on('error', (err) => {
    console.error('Error fetching URL:', err);
  });
}

fetch('https://mangadistrict.com/series/', 'e:/Tarumi 2/Tarumi/scratch/manga_dir.html');
