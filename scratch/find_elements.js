const fs = require('fs');

const data = fs.readFileSync('e:/Tarumi 2/Tarumi/scratch/manga_dir.html', 'utf8');

// Find all HTML blocks containing "/series/" (excluding /series/page/ or /series/feed/)
// Let's print out lines around links matching /series/[a-zA-Z0-9-]+/ (manga link)
const regex = /<div[^>]*>[\s\S]*?href="https:\/\/mangadistrict\.com\/series\/([a-zA-Z0-9-]+)\/"[\s\S]*?<\/div>/g;
let match;
let count = 0;
while ((match = regex.exec(data)) !== null && count < 10) {
  const block = match[0];
  const slug = match[1];
  if (slug === 'page' || slug === 'feed') continue;
  console.log(`BLOCK FOR ${slug}:`);
  console.log(block.substring(0, 500));
  console.log('----------------------------------------------------');
  count++;
}
