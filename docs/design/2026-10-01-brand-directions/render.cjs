const fs = require('fs');
const path = require('path');
const sharp = require('sharp');
const dir = __dirname;
const palettes = JSON.parse(fs.readFileSync(path.join(dir,'palettes.json'),'utf8'));
(async()=>{
  for (const p of palettes) {
    const base = `${p.id}-concept`;
    await sharp(path.join(dir,base+'.svg')).png().toFile(path.join(dir,base+'.png'));
  }
  await sharp(path.join(dir,'04-revision-r5.svg')).png().toFile(path.join(dir,'04-revision-r5.png'));
  for (let i=0;i<6;i+=2) {
    const layers = await Promise.all(palettes.slice(i,i+2).map(async(p,j)=>({
      input:await sharp(path.join(dir,p.id+'-concept.png')).resize(1152,768).toBuffer(),
      left:j*1152,top:0
    })));
    await sharp({create:{width:2304,height:768,channels:4,background:'#ece7dd'}})
      .composite(layers).png().toFile(path.join(dir,`pair-${palettes[i].id}-${palettes[i+1].id}.png`));
  }
  const cards=palettes.map(p=>`<article id="c${p.id}"><h2>${p.id} / ${p.name} <span>${p.mark}</span></h2><a href="${p.id}-concept.png"><img src="${p.id}-concept.png" alt="Палитра и монограмма ${p.name}"></a><p>${p.note}</p><p><a href="${p.id}-concept.svg">Композиция SVG</a> · <a href="${p.id}-logo.svg">Знак SVG</a> · <a href="${p.id}-logo-mono.svg">Одноцветный SVG</a></p></article>`).join('');
  fs.writeFileSync(path.join(dir,'index.html'),`<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>YMPlayer — цвет и знак</title><style>*{box-sizing:border-box}body{margin:0;background:#eeebe4;color:#252622;font:16px 'Segoe UI',sans-serif}header{max-width:1450px;margin:auto;padding:40px 24px 24px}h1{font-size:36px;margin:0 0 12px}p{line-height:1.6}nav{display:flex;gap:20px;flex-wrap:wrap}a{color:#704834}main{max-width:1450px;margin:auto;padding:0 24px 50px;display:grid;grid-template-columns:1fr 1fr;gap:24px}article{background:#f8f6f0;border:1px solid #d6d1c5;border-radius:8px;padding:16px}h2{font-size:22px;margin:0 0 15px}h2 span{color:#776f64;font-weight:400}img{display:block;width:100%;height:auto}article p{margin:12px 0 0}a:focus-visible{outline:3px solid #704834;outline-offset:4px}@media(max-width:850px){main{grid-template-columns:1fr}h1{font-size:28px}}</style><header><h1>YMPlayer. Цвет и знак.</h1><p>Шесть предложений для выбора. Палитру и монограмму можно сочетать независимо.<br>Нажмите на изображение, чтобы открыть полноразмерный вариант. Приложение ещё не изменено.</p><nav>${palettes.map(p=>`<a href="#c${p.id}">${p.id} ${p.name}</a>`).join('')}</nav></header><main>${cards}</main></html>`,'utf8');
  console.log('Six PNG concepts, three comparisons, and local gallery rendered.');
})().catch(e=>{console.error(e);process.exit(1)});
