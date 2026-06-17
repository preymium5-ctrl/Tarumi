const fs = require('fs');

process.on('uncaughtException', (err) => {});
process.on('unhandledRejection', (reason) => {});

class MockCanvas {
    constructor(w, h) { this.width = w || 300; this.height = h || 150; this.style = {}; }
    getContext(type) {
        if (type === '2d') return {
            fillRect:()=>{}, clearRect:()=>{}, getImageData:(x,y,w,h)=>({data:new Uint8ClampedArray(w*h*4),width:w,height:h}),
            putImageData:()=>{}, createImageData:(w,h)=>({data:new Uint8ClampedArray(w*h*4),width:w,height:h}),
            setTransform:()=>{}, drawImage:()=>{}, save:()=>{}, fillText:()=>{}, restore:()=>{},
            beginPath:()=>{}, moveTo:()=>{}, lineTo:()=>{}, closePath:()=>{}, stroke:()=>{},
            translate:()=>{}, scale:()=>{}, rotate:()=>{}, arc:()=>{}, fill:()=>{},
            measureText:(t)=>({width:t.length*7}), transform:()=>{},
            font:'', textBaseline:'', textAlign:'', fillStyle:'', strokeStyle:'',
            globalAlpha:1, globalCompositeOperation:'source-over', canvas:this,
        };
        if (type==='webgl'||type==='webgl2') return {getExtension:()=>null, getParameter:(p)=>p===37446?'Intel':p===37445?'Google Inc.':'', getSupportedExtensions:()=>[]};
        return null;
    }
    toDataURL() { return 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUg=='; }
    toBlob(cb) { cb(new Blob()); } setAttribute() {} getAttribute() { return null; }
}
class MockImageData {
    constructor(w, h) {
        if (w instanceof Uint8ClampedArray) { this.data = w; this.width = h; this.height = arguments[2] || 1; }
        else { this.width = w||1; this.height = h||1; this.data = new Uint8ClampedArray(this.width*this.height*4); }
    }
}
global.ImageData = MockImageData;

const cfgContent = 'ZZYdbXagjEpeaRwTE56mTpBkKVnnIBmAB3gdwWXXjEM7ZqAcLgonw0ylNjY621zM0zefn1Qg_jIQEn0oAIFnaXeGk3K4XZgY6S1Ldadwahluywsju2Z_xXiMDsD2';
const metaMock = { name:'cfg', content:cfgContent, getAttribute(a){return a==='name'?'cfg':a==='content'?cfgContent:null;} };
const documentMock = {
    documentElement:{outerHTML:'<html></html>',innerText:'',style:{}}, body:{innerText:'',appendChild:()=>{},removeChild:()=>{},style:{}},
    title:'', hidden:false, visibilityState:'visible', addEventListener:()=>{}, removeEventListener:()=>{},
    getElementById:()=>null, querySelector:(s)=>s==='[disable-devtool-auto]'?null:s==='meta[name="cfg"]'?metaMock:null,
    querySelectorAll:(s)=>s==='meta'?[metaMock]:[], getElementsByTagName:(t)=>t==='meta'?[metaMock]:[],
    createElement:(tag)=>{if(tag==='canvas')return new MockCanvas();return{style:{},setAttribute:()=>{},getAttribute:()=>null,appendChild:()=>{},removeChild:()=>{},addEventListener:()=>{},removeEventListener:()=>{},classList:{add:()=>{},remove:()=>{},contains:()=>false},innerHTML:'',innerText:'',textContent:'',tagName:tag.toUpperCase()};},
    createTextNode:(t)=>({textContent:t}), cookie:'',
};

Object.defineProperty(global,'navigator',{value:{userAgent:'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',appCodeName:'Mozilla',platform:'Win32',language:'en-US',languages:['en-US','en'],cookieEnabled:true,hardwareConcurrency:8,maxTouchPoints:0,vendor:'Google Inc.',plugins:{length:0},mimeTypes:{length:0}},configurable:true,writable:true});
Object.defineProperty(global,'location',{value:{href:'https://comix.to/',protocol:'https:',hostname:'comix.to',host:'comix.to',origin:'https://comix.to',pathname:'/',search:'',hash:'',port:''},configurable:true,writable:true});
Object.defineProperty(global,'document',{value:documentMock,configurable:true,writable:true});

global.HTMLCanvasElement=MockCanvas; global.CanvasRenderingContext2D=function(){}; global.OffscreenCanvasRenderingContext2D=function(){};
global.OffscreenCanvas=MockCanvas; global.WebGLRenderingContext=function(){}; global.WebGL2RenderingContext=function(){};
global.createImageBitmap=async()=>({width:1,height:1,close:()=>{}}); global.Document=function(){}; global.open=()=>null;
global.top=global; global.parent=global; global.innerWidth=1920; global.innerHeight=1080;
global.screen={width:1920,height:1080,availWidth:1920,availHeight:1040,colorDepth:24,pixelDepth:24};
global.devicePixelRatio=1; global.performance={now:()=>Date.now(),timing:{navigationStart:Date.now()}};
global.requestAnimationFrame=(cb)=>setTimeout(cb,16); global.cancelAnimationFrame=(id)=>clearTimeout(id);
global.MutationObserver=class{constructor(){}observe(){}disconnect(){}}; global.ResizeObserver=class{constructor(){}observe(){}disconnect(){}};
global.IntersectionObserver=class{constructor(){}observe(){}disconnect(){}};
global.localStorage={getItem:()=>null,setItem:()=>{},removeItem:()=>{},clear:()=>{}}; global.sessionStorage={getItem:()=>null,setItem:()=>{},removeItem:()=>{},clear:()=>{}};
global.Blob=class{constructor(){this.size=0;this.type=''}}; global.URL={createObjectURL:()=>'blob:null',revokeObjectURL:()=>{}};

global.window = new Proxy(global, {
    get(t,p) { if(p==='document')return documentMock; return t[p]; },
    set(t,p,v) { t[p]=v; return true; }
});
global.fetch = async () => { throw new Error('fetch not available'); };

// ---- Run secure.js ----
const code = fs.readFileSync('secure.js', 'utf8');
const cleanCode = code.replace(/export\s*\{[^\}]*\}\s*;?\s*$/, '');
const runFn = new Function('window', 'globalThis', 'document', cleanCode);
runFn(global.window, global.window, documentMock);

const vmKeys = Object.keys(global).filter(k => /^vm[A-Za-z]_\w+$/.test(k));
const vmObj = global[vmKeys[0]];

// Examine the WASM module
console.log("=== EXAMINING WASM MODULE ===");
const wasmB64 = global.uo;
if (wasmB64) {
    const wasmBytes = Buffer.from(wasmB64, 'base64');
    console.log("WASM bytes length:", wasmBytes.length);
    
    // Compile and instantiate the WASM module
    (async () => {
        const wasmModule = await WebAssembly.compile(wasmBytes);
        const exports = WebAssembly.Module.exports(wasmModule);
        console.log("WASM exports:", exports.map(e => `${e.name}(${e.kind})`).join(', '));
        
        // Instantiate with empty imports
        const instance = await WebAssembly.instantiate(wasmModule, {});
        const wasmExports = instance.exports;
        
        console.log("\nWASM function signatures:");
        for (const exp of exports) {
            if (exp.kind === 'function') {
                console.log(`  ${exp.name}: ${typeof wasmExports[exp.name]}`);
            }
        }
        
        // Test the WASM functions
        console.log("\n=== TESTING WASM FUNCTIONS ===");
        
        // Prepare a path in WASM memory
        const memory = wasmExports.memory;
        const path = "/manga/g2rk/chapters";
        const pathBytes = Buffer.from(path, 'utf8');
        
        // Write path to WASM memory at offset 0
        const memView = new Uint8Array(memory.buffer);
        for (let i = 0; i < pathBytes.length; i++) {
            memView[i] = pathBytes[i];
        }
        
        // Test buildOrderV1(ptr, len) -> result
        try {
            const result1 = wasmExports.buildOrderV1(0, pathBytes.length);
            console.log(`buildOrderV1(0, ${pathBytes.length}) = ${result1}`);
        } catch(e) {
            console.log(`buildOrderV1 error: ${e.message}`);
        }
        
        // Test buildOrderV2(ptr, len) -> result
        try {
            const result2 = wasmExports.buildOrderV2(0, pathBytes.length);
            console.log(`buildOrderV2(0, ${pathBytes.length}) = ${result2}`);
        } catch(e) {
            console.log(`buildOrderV2 error: ${e.message}`);
        }
        
        // Test xorPrefixV1(ptr, len, key) -> result
        try {
            const result3 = wasmExports.xorPrefixV1(0, pathBytes.length);
            console.log(`xorPrefixV1(0, ${pathBytes.length}) = ${result3}`);
        } catch(e) {
            console.log(`xorPrefixV1 error: ${e.message}`);
        }
        
        // Test xorPrefixV2(ptr, len, key) -> result
        try {
            const result4 = wasmExports.xorPrefixV2(0, pathBytes.length);
            console.log(`xorPrefixV2(0, ${pathBytes.length}) = ${result4}`);
        } catch(e) {
            console.log(`xorPrefixV2 error: ${e.message}`);
        }
        
        // Now let's examine key properties on the vmObj more carefully
        console.log("\n=== EXAMINING ALL GLOBAL PROPERTIES SET BY VM ===");
        const interestingKeys = ['Gi', 'Mi', 'Ui', 'xi', 't', 'Li', 'Hi', 'Ki', 'n', 'oo', 'lo', 'vo', 'ao'];
        for (const key of interestingKeys) {
            const val = global[key];
            if (val === undefined || val === null) {
                console.log(`  global.${key} = ${val}`);
            } else if (typeof val === 'string') {
                console.log(`  global.${key} = "${val.substring(0, 100)}"`);
            } else if (typeof val === 'number' || typeof val === 'boolean') {
                console.log(`  global.${key} = ${val}`);
            } else if (typeof val === 'object') {
                const keys = Object.keys(val);
                console.log(`  global.${key} = {${keys.join(', ')}} (${keys.length} keys)`);
            } else {
                console.log(`  global.${key} = (${typeof val})`);
            }
        }
        
        // The 't' object is interesting - it was set to an object
        console.log("\n=== EXAMINING global.t ===");
        if (global.t && typeof global.t === 'object') {
            for (const [k, v] of Object.entries(global.t)) {
                if (typeof v === 'function') {
                    console.log(`  t.${k} = function`);
                    try {
                        const out = v("/manga/g2rk/chapters");
                        console.log(`    t.${k}("...") = ${typeof out === 'string' ? '"'+out.substring(0,100)+'"' : typeof out}`);
                    } catch(e) {
                        console.log(`    t.${k}("...") ERROR: ${e.message.substring(0, 80)}`);
                    }
                } else if (typeof v === 'string') {
                    console.log(`  t.${k} = "${v.substring(0, 80)}"`);
                } else {
                    console.log(`  t.${k} = ${typeof v}: ${String(v).substring(0, 80)}`);
                }
            }
        }
        
        // Also check 'n' which is also an object
        console.log("\n=== EXAMINING global.n ===");
        if (global.n && typeof global.n === 'object') {
            for (const [k, v] of Object.entries(global.n)) {
                if (typeof v === 'function') {
                    console.log(`  n.${k} = function`);
                } else {
                    console.log(`  n.${k} = ${typeof v}: ${String(v).substring(0, 80)}`);
                }
            }
        }

        // Check 'oo' 
        console.log("\n=== EXAMINING global.oo ===");
        if (global.oo && typeof global.oo === 'object') {
            for (const [k, v] of Object.entries(global.oo)) {
                if (typeof v === 'function') {
                    console.log(`  oo.${k} = function`);
                } else {
                    console.log(`  oo.${k} = ${typeof v}: ${String(v).substring(0, 80)}`);
                }
            }
        }
        
        // Check what Ri contains (it was printed as multiple functions)
        console.log("\n=== EXAMINING global.Ri ===");
        const ri = global.Ri;
        if (ri) {
            if (Array.isArray(ri)) {
                console.log(`Ri is array with ${ri.length} items`);
                for (let i = 0; i < ri.length; i++) {
                    if (typeof ri[i] === 'function') {
                        try {
                            const out = ri[i]("/manga/g2rk/chapters");
                            console.log(`  Ri[${i}]("...") = ${typeof out}: ${String(out).substring(0, 100)}`);
                        } catch(e) {
                            console.log(`  Ri[${i}]("...") ERROR: ${e.message.substring(0, 80)}`);
                        }
                    }
                }
            } else if (typeof ri === 'function') {
                console.log("Ri is a function");
            } else {
                console.log(`Ri is ${typeof ri}`);
            }
        }
        
        console.log("\n=== DONE ===");
        process.exit(0);
    })().catch(e => { console.error("Async error:", e); process.exit(1); });
    
} else {
    console.log("No WASM module found in global.uo");
    process.exit(1);
}
