const fs = require('fs');

process.on('uncaughtException', (err) => {});
process.on('unhandledRejection', (reason) => {});

// ---- Mock DOM Environment (same as before) ----
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
    toBlob(cb) { cb(new Blob()); }
    setAttribute() {} getAttribute() { return null; }
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
    get(target, prop) { if(prop==='document')return documentMock; return target[prop]; },
    set(target, prop, value) { target[prop]=value; return true; }
});

// Deep fetch interception - capture ALL fetch calls with full details
const fetchCalls = [];
global.fetch = async (url, options) => {
    const entry = { url: String(url), options: options ? JSON.parse(JSON.stringify(options)) : undefined };
    fetchCalls.push(entry);
    console.log(`[FETCH] url="${url}" options=${JSON.stringify(options||{})}`);
    
    // Actually try to make the request to comix.to to get a real response
    let targetUrl = String(url);
    if (targetUrl.startsWith('/')) targetUrl = 'https://comix.to' + targetUrl;
    
    // Use native fetch to actually call comix.to
    const nodeFetch = require('https');
    return new Promise((resolve, reject) => {
        const parsedUrl = new (require('url').URL)(targetUrl);
        const reqOptions = {
            hostname: parsedUrl.hostname,
            port: parsedUrl.port || 443,
            path: parsedUrl.pathname + parsedUrl.search,
            method: (options && options.method) || 'GET',
            headers: {
                'Accept': 'application/json',
                'X-Requested-With': 'XMLHttpRequest',
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
                ...(options && options.headers ? options.headers : {}),
            }
        };
        
        const req = nodeFetch.request(reqOptions, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                console.log(`[FETCH RESPONSE] status=${res.statusCode} body=${data.substring(0, 200)}`);
                resolve({
                    ok: res.statusCode >= 200 && res.statusCode < 300,
                    status: res.statusCode,
                    statusText: res.statusMessage,
                    text: async () => data,
                    json: async () => JSON.parse(data),
                    headers: {
                        get: (h) => res.headers[h.toLowerCase()],
                        entries: () => Object.entries(res.headers),
                    }
                });
            });
        });
        req.on('error', (e) => {
            console.log(`[FETCH ERROR] ${e.message}`);
            reject(e);
        });
        req.end();
    });
};

// ---- Run secure.js ----
try {
    const code = fs.readFileSync('secure.js', 'utf8');
    const cleanCode = code.replace(/export\s*\{[^\}]*\}\s*;?\s*$/, '');
    const runFn = new Function('window', 'globalThis', 'document', cleanCode);
    runFn(global.window, global.window, documentMock);
    
    const vmKeys = Object.keys(global).filter(k => /^vm[A-Za-z]_\w+$/.test(k));
    console.log("VM keys found:", vmKeys);
    
    const vmObj = global[vmKeys[0]];
    
    // Now invoke $r (the protected fetch) and see what happens
    console.log("\n=== TESTING vmObj.$r with real fetch ===");
    const probePath = "/manga/g2rk/chapters";
    
    (async () => {
        try {
            fetchCalls.length = 0;
            const result = await vmObj.$r(probePath);
            console.log("\n$r result type:", typeof result);
            console.log("$r result:", JSON.stringify(result).substring(0, 500));
        } catch (e) {
            console.log("\n$r error:", e.message);
        }
        
        console.log("\nAll fetch calls made:");
        for (const call of fetchCalls) {
            console.log(`  URL: ${call.url}`);
            if (call.options) console.log(`  Options: ${JSON.stringify(call.options)}`);
        }
        
        console.log("\n=== DONE ===");
        process.exit(0);
    })();
    
} catch (err) {
    console.error("Execution failed:", err.message);
    console.error(err.stack);
}
