const fs = require('fs');
const code = fs.readFileSync('secure.js', 'utf8');
const start = 201000;
const end = 203000;
console.log(code.substring(start, end));
