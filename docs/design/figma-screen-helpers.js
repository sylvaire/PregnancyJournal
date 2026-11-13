
await figma.setCurrentPageAsync(figma.root.children[0]);
const page=figma.currentPage;
const ids=[];
await Promise.all(['Regular','Medium','Bold'].map(style=>figma.loadFontAsync({family:'Noto Sans SC',style})));
const vars=await figma.variables.getLocalVariablesAsync();const v=Object.fromEntries(vars.map(x=>[x.name,x]));
const styles=Object.fromEntries((await figma.getLocalTextStylesAsync()).map(x=>[x.name.replace('孕迹/',''),x]));
const primary=await figma.getNodeByIdAsync('7:2462'),secondary=await figma.getNodeByIdAsync('7:2470'),fieldMaster=await figma.getNodeByIdAsync('7:2485'),rowMaster=await figma.getNodeByIdAsync('7:2496');
function fill(n,c){n.fills=[figma.variables.setBoundVariableForPaint({type:'SOLID',color:{r:0,g:0,b:0}},'color',v['color/'+c])];}
function space(n,prop,num){if(v['space/'+num])n.setBoundVariable(prop,v['space/'+num]);else n[prop]=num;}
function radius(n,num=20){n.cornerRadius=num;for(const prop of ['topLeftRadius','topRightRadius','bottomLeftRadius','bottomRightRadius'])if(v['radius/'+num])n.setBoundVariable(prop,v['radius/'+num]);}
function box(p,name,w,dir='VERTICAL',bg=null,pad=0,gap=12){const n=figma.createAutoLayout(dir);p.appendChild(n);n.name=name;n.resize(w,10);n.primaryAxisSizingMode=dir==='HORIZONTAL'?'FIXED':'AUTO';n.counterAxisSizingMode=dir==='HORIZONTAL'?'AUTO':'FIXED';space(n,'itemSpacing',gap);for(const key of ['paddingTop','paddingBottom','paddingLeft','paddingRight'])space(n,key,pad);n.fills=[];if(bg)fill(n,bg);ids.push(n.id);return n;}
function text(p,str,style='body',color='ink',width){const n=figma.createText();p.appendChild(n);n.characters=str;n.textStyleId=styles[style].id;fill(n,color);if(width){n.textAutoResize='HEIGHT';n.resize(width,n.height);}ids.push(n.id);return n;}
function card(p,name='Card',color='surface'){const n=box(p,name,364,'VERTICAL',color,20,12);radius(n,20);return n;}
function label(p,a,b){const r=box(p,'Section',364,'HORIZONTAL',null,0,8);r.primaryAxisAlignItems='SPACE_BETWEEN';r.counterAxisAlignItems='CENTER';text(r,a,'heading');if(b)text(r,b,'label','primary');return r;}
function badge(p,str,c='soft'){const b=box(p,'Badge',110,'HORIZONTAL',c,8,0);b.primaryAxisSizingMode='AUTO';b.counterAxisSizingMode='AUTO';radius(b,12);text(b,str,'caption',c==='dangerSoft'?'danger':c==='apricot'?'amber':'primary');return b;}
async function fontsIn(n){for(const t of n.findAll(x=>x.type==='TEXT'))for(const s of t.getStyledTextSegments(['fontName']))await figma.loadFontAsync(s.fontName);}
async function button(p,str,variant='Primary'){const i=(variant==='Primary'?primary:secondary).createInstance();await fontsIn(i);p.appendChild(i);i.resize(364,52);i.name='Button · '+str;const t=i.findOne(n=>n.type==='TEXT');if(t)t.characters=str;ids.push(i.id,...i.findAll(()=>true).map(x=>x.id));return i;}
async function field(p,title,value){const i=fieldMaster.createInstance();await fontsIn(i);p.appendChild(i);i.resize(364,56);i.name='Field · '+title;const lab=i.findOne(n=>n.type==='TEXT'&&n.name==='Label text');const inp=i.findOne(n=>n.type==='TEXT'&&n.name==='Input text');if(lab)lab.characters=title;if(inp)inp.characters=value;ids.push(i.id,...i.findAll(()=>true).map(x=>x.id));return i;}
async function row(p,title,sub,symbol='＋'){const i=rowMaster.createInstance();await fontsIn(i);p.appendChild(i);i.resize(364,84);i.name='Record · '+title;const ts=i.findAll(n=>n.type==='TEXT');ts[0].characters=symbol;ts[1].characters=title;ts[2].characters=sub;ids.push(i.id,...i.findAll(()=>true).map(x=>x.id));return i;}
function line(p,w=324){const n=figma.createRectangle();p.appendChild(n);n.resize(w,1);fill(n,'line');ids.push(n.id);return n;}
const paths={
home:'M3 11L12 3l9 8v10h-6v-7H9v7H3z',
calendar:'M4 5h16v16H4z M4 10h16 M8 3v4 M16 3v4',
file:'M6 3h8l4 4v14H6z M14 3v5h4 M9 12h6 M9 16h6',
user:'M8 7a4 4 0 1 0 8 0a4 4 0 1 0-8 0 M4 21v-3c0-6 16-6 16 0v3',
shield:'M12 3l8 3v6c0 5-8 9-8 9s-8-4-8-9V6z M8 12l3 3 5-6',
plus:'M12 4v16 M4 12h16',leaf:'M5 20C2 4 12 2 21 3c1 12-5 19-16 17z M5 20L16 8',check:'M5 12l4 4L20 5',arrow:'M15 5l-7 7 7 7'};
function icon(p,key,color='primary',size=24){const n=figma.createNodeFromSvg('<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="'+paths[key]+'" stroke="#356B53" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>');p.appendChild(n);n.resize(size,size);for(const x of n.findAll(x=>x.type==='VECTOR')){x.strokes=[figma.variables.setBoundVariableForPaint({type:'SOLID',color:{r:0,g:0,b:0}},'color',v['color/'+color])];}ids.push(n.id,...n.findAll(()=>true).map(x=>x.id));return n;}
function nav(p,active){const n=box(p,'Bottom navigation',412,'HORIZONTAL','surface',16,0);n.counterAxisAlignItems='CENTER';for(const [i,title] of ['今天','产检','记录','我的'].entries()){const cell=box(n,'Nav · '+title,95,'VERTICAL',null,0,4);cell.counterAxisAlignItems='CENTER';const pill=box(cell,'Indicator',64,'HORIZONTAL',i===active?'soft':null,8,0);pill.primaryAxisAlignItems='CENTER';radius(pill,20);icon(pill,['home','calendar','file','user'][i],i===active?'primary':'muted');text(cell,title,'caption',i===active?'primary':'muted');}return n;}
function screen(board,name,title,subtitle,active=null){const wrap=box(board,name,412,'VERTICAL','bg',0,0);wrap.resize(412,915);wrap.primaryAxisSizingMode='FIXED';radius(wrap,28);wrap.clipsContent=true;
const status=box(wrap,'Android status bar',412,'HORIZONTAL',null,24,0);status.paddingTop=12;status.paddingBottom=4;status.primaryAxisAlignItems='SPACE_BETWEEN';text(status,'9:41','caption');text(status,'◖  ▰','caption');
const header=box(wrap,'App bar',412,'VERTICAL',null,24,4);header.paddingTop=16;header.paddingBottom=16;text(header,title,'title');if(subtitle)text(header,subtitle,'caption','muted',364);
const body=box(wrap,'Content',412,'VERTICAL',null,24,16);body.paddingTop=0;body.paddingBottom=16;body.layoutSizingVertical='FILL';body.clipsContent=true;
if(active!==null)nav(wrap,active);const gesture=box(wrap,'Gesture area',412,'HORIZONTAL',null,8,0);gesture.primaryAxisAlignItems='CENTER';const bar=figma.createRectangle();gesture.appendChild(bar);bar.resize(108,4);bar.cornerRadius=2;fill(bar,'ink');ids.push(bar.id);return {frame:wrap,body};}
function board(name,y){if(page.children.some(n=>n.name===name))throw new Error('Board exists: '+name);const n=box(page,name,1888,'VERTICAL','bg',32,24);n.x=100;n.y=y;text(n,'孕迹  /  '+name,'title');text(n,'V1.0 · 可编辑 UI 稿    /    示例内容均为虚构    /    2026.10','body','muted');return n;}
function screenRow(b){return box(b,'Screens',1824,'HORIZONTAL',null,0,48);}
function result(boards,screens){return {createdNodeIds:[...new Set(ids)],boards:boards.map(b=>({id:b.id,name:b.name,width:b.width,height:b.height})),screens:screens.map(s=>({id:s.frame.id,name:s.frame.name,width:s.frame.width,height:s.frame.height,bodyHeight:s.body.height,contentBottom:Math.max(0,...s.body.children.map(n=>n.y+n.height)),font:'Noto Sans SC'}))};}

