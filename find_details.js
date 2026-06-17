const fs = require('fs');
const content = fs.readFileSync('C:/Users/Rockz/.gemini/antigravity-ide/brain/f971f624-24b5-4843-9873-38300293c814/.system_generated/steps/2520/content.md', 'utf8');

const bodyOnly = content.replace(/<script[\s\S]*?<\/script>/gi, '');

let idx = -1;
let count = 0;
while ((idx = bodyOnly.toLowerCase().indexOf("reincarnation", idx + 1)) !== -1) {
    count++;
    console.log(`Match ${count} at Index ${idx}:`);
    console.log(bodyOnly.substring(idx - 100, idx + 400));
    console.log("==========================================");
    if (count > 5) break;
}
