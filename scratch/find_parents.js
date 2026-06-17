const fs = require('fs');
const data = fs.readFileSync('e:/Tarumi 2/Tarumi/scratch/demonic_chapter.html', 'utf8');

// Print HTML from 1000 characters before the first page image
const idx = data.indexOf('How to Send My Husband to the Abyss Chapter 61 1');
if (idx !== -1) {
  console.log(data.substring(idx - 1000, idx + 500));
} else {
  console.log('Not found');
}
