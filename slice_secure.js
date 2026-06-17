const fs = require('fs');
const code = fs.readFileSync('secure.js', 'utf8');
const start = 210000;
const end = 213000;
console.log(code.substring(start, end));
