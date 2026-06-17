const fs = require('fs');
const https = require('https');

function fetchUrl(url) {
    return new Promise((resolve, reject) => {
        https.get(url, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
            }
        }, (res) => {
            let data = '';
            res.on('data', (chunk) => data += chunk);
            res.on('end', () => resolve(data));
        }).on('error', reject);
    });
}

async function main() {
    try {
        console.log("Reading comix_home.html...");
        const html = fs.readFileSync('comix_home.html', 'utf8');
        
        // Find JS script source
        const match = html.match(/src="([^"]+main[^"]+\.js)"/);
        if (!match) {
            console.error("Could not find main JS script in comix_home.html");
            return;
        }
        
        const jsUrl = 'https://comix.to' + match[1];
        console.log("Fetching JS script...");
        const jsCode = await fetchUrl(jsUrl);
        
        console.log("Searching for window references...");
        let pos = 0;
        let windowRefs = [];
        while ((pos = jsCode.indexOf('window.', pos)) !== -1) {
            const snippet = jsCode.substring(Math.max(0, pos - 50), Math.min(jsCode.length, pos + 100));
            windowRefs.push(snippet);
            pos += 7;
        }
        console.log(`Found ${windowRefs.length} references to window.`);
        windowRefs.forEach((ref, idx) => {
            console.log(`[${idx}]: ${ref}`);
            console.log("------------------------");
        });
        
    } catch (e) {
        console.error("Error:", e);
    }
}

main();
