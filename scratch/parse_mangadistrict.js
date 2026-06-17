const fs = require('fs');

const data = fs.readFileSync('e:/Tarumi 2/Tarumi/scratch/manga_dir.html', 'utf8');

// Find all href links
const hrefs = [];
const regex = /href="([^"]+)"/g;
let match;
while ((match = regex.exec(data)) !== null) {
  hrefs.push(match[1]);
}

console.log('Total hrefs found:', hrefs.length);
const uniqueHrefs = [...new Set(hrefs)];
console.log('Unique hrefs:', uniqueHrefs.length);

const seriesHrefs = uniqueHrefs.filter(h => h.includes('/series/'));
console.log('Series hrefs count:', seriesHrefs.length);
console.log('Sample series hrefs:', seriesHrefs.slice(0, 10));

const publicationHrefs = uniqueHrefs.filter(h => h.includes('/publication-genre/'));
console.log('Publication-genre hrefs count:', publicationHrefs.length);

const readScanHrefs = uniqueHrefs.filter(h => h.includes('/read-scan/'));
console.log('Read-scan hrefs count:', readScanHrefs.length);
console.log('Sample read-scan hrefs:', readScanHrefs.slice(0, 10));
