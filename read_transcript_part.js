const fs = require('fs');

const logPath = 'C:/Users/Rockz/.gemini/antigravity-ide/brain/319026f8-0ea1-4778-91a8-40d36f595b07/.system_generated/logs/transcript.jsonl';
const lines = fs.readFileSync(logPath, 'utf8').split('\n');
const obj = JSON.parse(lines[16695]);
const content = obj.content;

// Search for keywords
const keywords = ["sign", "intercept", "axios", "decrypt", "t.", "appCodeName", "vmU"];
keywords.forEach(kw => {
    console.log(`\n=================== SEARCHING FOR: ${kw} ===================`);
    let pos = 0;
    let count = 0;
    while ((pos = content.toLowerCase().indexOf(kw.toLowerCase(), pos)) !== -1) {
        count++;
        console.log(`Match ${count} (index ${pos}):`);
        console.log(content.substring(Math.max(0, pos - 200), Math.min(content.length, pos + 300)));
        console.log("------------------");
        pos += kw.length;
        if (count >= 10) {
            console.log("...more matches truncated...");
            break;
        }
    }
});
