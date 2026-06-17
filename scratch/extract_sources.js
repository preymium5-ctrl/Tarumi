const fs = require('fs');
const path = require('path');
const https = require('https');

const parserDir = 'e:/Tarumi 2/kotatsu-parsers-redo/src/main/kotlin/org/koitharu/kotatsu/parsers/site';

// Recursively find all Kotlin files
function getFiles(dir) {
  let results = [];
  const list = fs.readdirSync(dir);
  list.forEach(file => {
    file = path.join(dir, file);
    const stat = fs.statSync(file);
    if (stat && stat.isDirectory()) {
      results = results.concat(getFiles(file));
    } else if (file.endsWith('.kt')) {
      results.push(file);
    }
  });
  return results;
}

const allFiles = getFiles(parserDir);
console.log('Total Kotlin files found:', allFiles.length);

const enNsfwSources = [];

allFiles.forEach(file => {
  const content = fs.readFileSync(file, 'utf8');
  if (content.includes('ContentType.HENTAI') && content.includes('"en"')) {
    // Extract Source ID and Title
    const annotationMatch = content.match(/@MangaSourceParser\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"en"/);
    if (annotationMatch) {
      const id = annotationMatch[1];
      const name = annotationMatch[2];
      
      // Try to find the domain string
      // Usually looks like: MadaraParser(..., "domain.com", ...) or domain = "domain.com"
      let domain = '';
      const domainMatch1 = content.match(/domain\s*=\s*"([^"]+)"/);
      const domainMatch2 = content.match(/MadaraParser\([^,]+,\s*[^,]+,\s*"([^"]+)"/);
      const domainMatch3 = content.match(/MangaReaderParser\([^,]+,\s*[^,]+,\s*"([^"]+)"/);
      const domainMatch4 = content.match(/Manga18Parser\([^,]+,\s*"([^"]+)"/);
      
      if (domainMatch1) domain = domainMatch1[1];
      else if (domainMatch2) domain = domainMatch2[1];
      else if (domainMatch3) domain = domainMatch3[1];
      else if (domainMatch4) domain = domainMatch4[1];
      else {
        // Look for any string literal with a TLD
        const strings = content.match(/"[a-zA-Z0-9-]+\.[a-z]{2,}"/g);
        if (strings) {
          domain = strings[0].replace(/"/g, '');
        }
      }
      
      enNsfwSources.push({
        id,
        name,
        domain: domain || 'unknown',
        file: file
      });
    }
  }
});

console.log(`Found ${enNsfwSources.length} English NSFW sources:`);
console.log(JSON.stringify(enNsfwSources, null, 2));

fs.writeFileSync('e:/Tarumi 2/Tarumi/scratch/en_nsfw_sources.json', JSON.stringify(enNsfwSources, null, 2));
