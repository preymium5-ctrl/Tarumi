const fs = require('fs');
const content = fs.readFileSync('C:/Users/Rockz/.gemini/antigravity-ide/brain/f971f624-24b5-4843-9873-38300293c814/.system_generated/steps/2520/content.md', 'utf8');

const bodyOnly = content.replace(/<script[\s\S]*?<\/script>/gi, '');

// Find all <h1>, <h2>, <h3>, <h4> tags
const tags = bodyOnly.match(/<h[1-6][^>]*>[\s\S]*?<\/h[1-6]>/gi) || [];
console.log("HEADERS:");
console.log(tags.slice(0, 30));

// Find div.grid or other grids
console.log("GRIDS / LABELS:");
let idx = -1;
const textLabels = ["author", "artist", "status", "type"];
textLabels.forEach(label => {
    let start = 0;
    console.log(`Searching for text: ${label}`);
    while ((idx = bodyOnly.toLowerCase().indexOf(label, start)) !== -1) {
        console.log(bodyOnly.substring(idx - 50, idx + 100));
        start = idx + 1;
        if (start > 50000) break; // limit
    }
});
