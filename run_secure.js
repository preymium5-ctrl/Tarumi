const fs = require('fs');

// Redefine read-only navigator in Node
Object.defineProperty(global, 'navigator', {
    value: {
        userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        appCodeName: 'Mozilla'
    },
    configurable: true,
    writable: true
});

global.window = global;

const mockMeta = {
    name: 'cfg',
    getAttribute: (name) => {
        if (name === 'content') {
            return 'ZZYdbXagjEpeaRwTE56mTpBkKVnnIBmAB3gdwWXXjEM7ZqAcLgonw0ylNjY621zM0zefn1Qg_jIQEn0oAIFnaXeGk3K4XZgY6S1Ldadwahluywsju2Z_xXiMDsD2';
        }
        return null;
    }
};

global.document = {
    documentElement: {},
    body: {},
    addEventListener: () => {},
    removeEventListener: () => {},
    getElementById: () => null,
    querySelector: () => mockMeta,
    querySelectorAll: () => [mockMeta],
    getElementsByTagName: (tag) => {
        if (tag === 'meta') {
            return [mockMeta];
        }
        return [];
    }
};

global.location = {
    href: 'https://comix.to/',
    protocol: 'https:',
    hostname: 'comix.to',
    pathname: '/'
};

try {
    const code = fs.readFileSync('secure.js', 'utf8');
    const cleanCode = code.replace(/export\s*\{[^\}]*\}\s*;?\s*$/, '');
    
    // Eval the clean code
    eval(cleanCode);
    
    // Find keys on window/global starting with vm
    const vmKeys = Object.keys(global).filter(k => k.startsWith('vm'));
    console.log("Found vm keys:", vmKeys);
    
    if (vmKeys.length === 0) {
        console.error("No vm keys found on global object!");
        process.exit(1);
    }
    
    const vmObj = global[vmKeys[0]];
    console.log("Analyzing object keys of", vmKeys[0]);
    
    // Test all functions on vmObj with a probe path
    const probePath = "/manga/g2rk/chapters";
    console.log(`\nTesting functions on ${vmKeys[0]} with path "${probePath}":`);
    
    for (const key of Object.keys(vmObj)) {
        const value = vmObj[key];
        if (typeof value === 'function') {
            try {
                const result = value(probePath);
                if (result) {
                    console.log(`  vmObj.${key}("${probePath}") -> type: ${typeof result}, length: ${result.length}, value: ${result}`);
                }
            } catch (e) {
                // console.log(`  vmObj.${key} failed:`, e.message);
            }
        }
    }
    
} catch (err) {
    console.error("Error running secure.js:", err);
}
