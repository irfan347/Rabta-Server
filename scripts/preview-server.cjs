// Disposable staging only. Explicit allow-list prevents inherited production DB/push credentials.
const {spawn}=require('node:child_process');
const path=require('node:path');
const origin=process.argv[2];
if(!origin || !/^https:\/\/[a-z0-9-]+\.trycloudflare\.com$/.test(origin)) {
  console.error('Usage: node scripts/preview-server.cjs https://NAME.trycloudflare.com');process.exit(1);
}
const server=spawn(process.execPath,['index.js'],{
  cwd:path.resolve(__dirname,'..'),stdio:'inherit',
  env:{PATH:process.env.PATH,HOST:'127.0.0.1',PORT:'38731',RABTA_WEB_ORIGIN:origin}
});
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>server.kill(signal));
server.on('exit',code=>process.exit(code??1));
