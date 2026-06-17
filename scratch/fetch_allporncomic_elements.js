const https = require('https');

const options = {
  hostname: 'allporncomic.com',
  port: 443,
  path: '/page/1/?s=&post_type=wp-manga',
  method: 'GET',
  headers: {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.5',
    'Connection': 'keep-alive',
  }
};

const req = https.request(options, (res) => {
  let body = '';
  res.on('data', (chunk) => {
    body += chunk;
  });

  res.on('end', () => {
    // Search for actual HTML tags
    const hasClassDetail = body.includes('class="page-item-detail') || body.includes("class='page-item-detail") || body.includes('class="row c-tabs-item__content');
    console.log(`CONTAINS ACTUAL MANGA HTML ELEMENTS: ${hasClassDetail}`);
    if (hasClassDetail) {
      console.log("Found actual elements!");
      // find first occurrence and print
      let idx = body.indexOf('class="page-item-detail');
      if (idx === -1) idx = body.indexOf("class='page-item-detail");
      if (idx === -1) idx = body.indexOf('class="row c-tabs-item__content');
      console.log(body.substring(idx - 100, idx + 800));
    } else {
      console.log("No actual elements found. Let's list some random class names or search result messages in the body.");
      // check if it says "No results found" or similar
      console.log("Contains 'No results' / 'Nothing found'?", body.includes('No results') || body.includes('Nothing') || body.includes('not found') || body.includes('found'));
      // print first 2000 chars of body
      console.log(body.substring(0, 1000));
    }
  });
});

req.on('error', (e) => {
  console.error(`problem with request: ${e.message}`);
});

req.end();
