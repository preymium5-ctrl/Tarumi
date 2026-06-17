const fs = require('fs');
const content = fs.readFileSync('C:/Users/Rockz/.gemini/antigravity-ide/brain/f971f624-24b5-4843-9873-38300293c814/.system_generated/steps/2520/content.md', 'utf8');

const mainMatch = content.match(/<main[\s\S]*?<\/main>/i);
if (mainMatch) {
    const mainHtml = mainMatch[0];
    const cleanMain = mainHtml.replace(/<script[\s\S]*?<\/script>/gi, '');
    console.log(cleanMain.substring(0, 10000));
} else {
    console.log("No main tag");
}
