const fs = require('fs');
const html = fs.readFileSync('comix_home.html', 'utf8');

const scriptRegex = /<script\b[^>]*>([\s\S]*?)<\/script>/gi;
const linkRegex = /<link\b[^>]*>/gi;

let match;
console.log("--- SCRIPTS ---");
while ((match = scriptRegex.exec(html)) !== null) {
    const openingTag = match[0].split('>')[0] + '>';
    console.log(openingTag);
}

console.log("--- LINKS ---");
while ((match = linkRegex.exec(html)) !== null) {
    console.log(match[0]);
}
