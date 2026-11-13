
await figma.setCurrentPageAsync(figma.root.children[0]);
const page=figma.currentPage,created=[],mutated=[],removed=[];
const screens=page.children.filter(n=>n.type==='FRAME'&&n.children.some(c=>c.name==='App bar'));
const fonts=new Map();for(const s of screens)for(const t of s.findAll(n=>n.type==='TEXT'))for(const seg of t.getStyledTextSegments(['fontName']))fonts.set(JSON.stringify(seg.fontName),seg.fontName);
const masters=await Promise.all(['7:2462','7:2470','7:2477','7:2496'].map(id=>figma.getNodeByIdAsync(id)));
for(const s of masters)for(const t of s.findAll(n=>n.type==='TEXT'))for(const seg of t.getStyledTextSegments(['fontName']))fonts.set(JSON.stringify(seg.fontName),seg.fontName);
await Promise.all([...fonts.values()].map(f=>figma.loadFontAsync(f)));
const vars=Object.fromEntries((await figma.variables.getLocalVariablesAsync()).map(v=>[v.name,v]));
const styles=Object.fromEntries((await figma.getLocalTextStylesAsync()).map(s=>[s.name.replace('孕迹/',''),s]));
function paint(c){return figma.variables.setBoundVariableForPaint({type:'SOLID',color:{r:0,g:0,b:0}},'color',vars['color/'+c]);}
function fill(n,c){n.fills=[paint(c)];}
function rad(n,r){n.cornerRadius=r;if(vars['radius/'+r])for(const p of ['topLeftRadius','topRightRadius','bottomLeftRadius','bottomRightRadius'])n.setBoundVariable(p,vars['radius/'+r]);}
function tx(p,str,style='caption',color='muted'){const n=figma.createText();p.appendChild(n);n.characters=str;n.textStyleId=styles[style].id;fill(n,color);created.push(n.id);return n;}
const paths={
check:'M5 12l4 4L20 5',plus:'M12 5v14 M5 12h14',
file:'M6 3h8l4 4v14H6z M14 3v5h4 M9 12h6 M9 16h6',
clock:'M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0 M12 7v5l3 2',
calendar:'M4 5h16v16H4z M4 10h16 M8 3v4 M16 3v4',
shield:'M12 3l8 3v6c0 5-8 9-8 9s-8-4-8-9V6z M8 12l3 3 5-6',
pill:'M7 17L17 7 M6 8l2-2a6 6 0 0 1 8 8l-2 2a6 6 0 0 1-8-8z',
scale:'M5 5h14v15H5z M8 8h8v5H8z M12 9l2 2',
restore:'M4 9a8 8 0 1 1 0 7 M4 3v6h6',
chevron:'M9 5l7 7-7 7'};
function vector(p,key,size=24,color='primary'){const svg=figma.createNodeFromSvg('<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none"><path d="'+paths[key]+'" stroke="#356B53" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/></svg>');p.appendChild(svg);svg.resize(size,size);for(const n of svg.findAll(n=>n.type==='VECTOR'))n.strokes=[paint(color)];created.push(svg.id,...svg.findAll(()=>true).map(n=>n.id));return svg;}
let iconArea=page.children.find(n=>n.name==='孕迹 · 精修图标');
if(iconArea)throw new Error('Detail icons exist; inspect before replay');
iconArea=figma.createAutoLayout('HORIZONTAL');page.appendChild(iconArea);iconArea.name='孕迹 · 精修图标';iconArea.x=1400;iconArea.y=650;iconArea.itemSpacing=16;iconArea.fills=[];created.push(iconArea.id);
const icons={};
for(const key of Object.keys(paths)){const c=figma.createComponent();iconArea.appendChild(c);c.name='孕迹/Icon/'+key;c.description='24 × 24 统一线性图标，1.75 描边。';c.resize(24,24);c.fills=[];vector(c,key);icons[key]=c;created.push(c.id);}
const rowMaster=masters[3];const allRows=screens.flatMap(s=>s.findAll(n=>n.type==='INSTANCE'&&n.name.startsWith('Record ·')));
const rowInfo=allRows.map(n=>({id:n.id,name:n.name,symbol:n.findAll(x=>x.type==='TEXT')[0]?.characters}));
const iconContainer=rowMaster.children.find(n=>n.name==='Icon');
for(const n of [...iconContainer.children]){removed.push(n.id);n.remove();}
iconContainer.resize(44,44);iconContainer.primaryAxisSizingMode='FIXED';iconContainer.counterAxisSizingMode='FIXED';iconContainer.primaryAxisAlignItems='CENTER';iconContainer.counterAxisAlignItems='CENTER';iconContainer.paddingTop=iconContainer.paddingBottom=iconContainer.paddingLeft=iconContainer.paddingRight=10;rad(iconContainer,12);
const iconProp=rowMaster.addComponentProperty('Leading icon','INSTANCE_SWAP',icons.file.id);const lead=icons.file.createInstance();iconContainer.appendChild(lead);lead.componentPropertyReferences={mainComponent:iconProp};created.push(lead.id);
const oldChevron=rowMaster.children.find(n=>n.type==='TEXT'&&n.characters==='›');if(oldChevron){removed.push(oldChevron.id);oldChevron.remove();}
const chevron=icons.chevron.createInstance();rowMaster.appendChild(chevron);chevron.resize(20,20);created.push(chevron.id);
const showChevron=rowMaster.addComponentProperty('Show chevron','BOOLEAN',true);chevron.componentPropertyReferences={visible:showChevron};
rowMaster.resize(364,72);rowMaster.paddingTop=rowMaster.paddingBottom=12;rowMaster.paddingLeft=rowMaster.paddingRight=16;rowMaster.counterAxisAlignItems='CENTER';rowMaster.counterAxisSizingMode='FIXED';mutated.push(rowMaster.id,iconContainer.id);
for(const info of rowInfo){const n=await figma.getNodeByIdAsync(info.id);let key='file';
if(/用药|待确认|待到时间/.test(info.name))key='pill';
else if(/体重|kg/.test(info.name))key='scale';
else if(/产检|复诊|18:00/.test(info.name))key='calendar';
else if(/隐私|密钥|备份设备/.test(info.name))key='shield';
else if(/回收站/.test(info.name))key='restore';
else if(info.symbol==='◷')key='clock';
else if(info.symbol==='✓')key='check';
else if(info.symbol==='＋')key='plus';
n.setProperties({[iconProp]:icons[key].id,[showChevron]:! /资料随身|换一台手机|检查图片 0|检查报告 PDF/.test(info.name)});
n.resize(364,72);n.counterAxisSizingMode='FIXED';n.paddingTop=n.paddingBottom=12;
const ic=n.findOne(x=>x.type==='FRAME'&&x.name==='Icon');if(ic){ic.resize(44,44);ic.primaryAxisSizingMode='FIXED';ic.counterAxisSizingMode='FIXED';mutated.push(ic.id);}
mutated.push(n.id);}
for(const m of masters.slice(0,3)){m.resize(364,52);m.primaryAxisSizingMode='FIXED';m.counterAxisSizingMode='FIXED';const root=m.children[0];root.resize(364,52);root.primaryAxisSizingMode='FIXED';root.counterAxisSizingMode='FIXED';const content=root.findOne(n=>n.name==='Content'&&n.type==='FRAME');const layer=root.findOne(n=>n.name==='State-layer'&&n.type==='FRAME');
for(const n of [content,layer].filter(Boolean)){n.resize(364,52);n.primaryAxisSizingMode='FIXED';n.counterAxisSizingMode='FIXED';n.primaryAxisAlignItems='CENTER';n.counterAxisAlignItems='CENTER';rad(n,28);mutated.push(n.id);}mutated.push(m.id,root.id);}
for(const s of screens){
for(const btn of s.findAll(n=>n.type==='INSTANCE'&&n.name.startsWith('Button ·'))){btn.resize(364,52);const r=btn.children[0];r.resize(364,52);for(const n of r.findAll(n=>n.type==='FRAME'&&['Content','State-layer'].includes(n.name))){n.resize(364,52);n.primaryAxisSizingMode='FIXED';n.counterAxisSizingMode='FIXED';n.primaryAxisAlignItems='CENTER';n.counterAxisAlignItems='CENTER';mutated.push(n.id);}mutated.push(btn.id,r.id);}
const st=s.children.find(n=>n.name==='Android status bar');for(const n of [...st.children]){removed.push(n.id);n.remove();}
st.layoutMode='NONE';st.resize(412,34);const time=tx(st,'9:41','caption','ink');time.x=24;time.y=10;
const sys=figma.createNodeFromSvg('<svg xmlns="http://www.w3.org/2000/svg" width="48" height="16" viewBox="0 0 48 16"><path d="M2 12V9h3v3M7 12V6h3v6M12 12V3h3v9" stroke="#223C32" stroke-width="2"/><path d="M20 6q5-5 10 0M22 9q3-3 6 0M25 12h.01" fill="none" stroke="#223C32" stroke-width="1.5" stroke-linecap="round"/><rect x="34" y="4" width="11" height="8" rx="2" fill="none" stroke="#223C32" stroke-width="1.5"/><path d="M46 6v4" stroke="#223C32"/><rect x="36" y="6" width="7" height="4" rx=".5" fill="#223C32"/></svg>');st.appendChild(sys);sys.x=340;sys.y=10;created.push(sys.id,...sys.findAll(()=>true).map(x=>x.id));mutated.push(st.id);
for(const group of s.findAll(n=>n.type==='FRAME'&&/tabs|filters|sections/.test(n.name))){group.resize(364,48);group.primaryAxisSizingMode='FIXED';group.counterAxisSizingMode='FIXED';group.itemSpacing=8;group.counterAxisAlignItems='CENTER';for(let i=0;i<group.children.length;i++){const c=group.children[i],t=c.findOne(n=>n.type==='TEXT');if(!t)continue;t.textStyleId=styles.label.id;fill(t,i===0?'surface':'muted');const w=Math.max(64,Math.ceil(t.characters.length*14)+28);c.resize(w,48);c.primaryAxisSizingMode='FIXED';c.counterAxisSizingMode='FIXED';c.primaryAxisAlignItems='CENTER';c.counterAxisAlignItems='CENTER';c.paddingTop=c.paddingBottom=0;c.paddingLeft=c.paddingRight=12;rad(c,12);fill(c,i===0?'primary':'surface');c.strokes=i===0?[]:[paint('line')];c.strokeWeight=1;mutated.push(c.id,t.id);}mutated.push(group.id);}
for(const n of s.findAll(n=>n.type==='TEXT')){let value=n.characters;
value=value.replace(/(\d) ?周 ?\+ ?(\d) ?天/g,'$1周 + $2天').replace(/(\d)月(\d)日/g,'$1月$2日').replace(/(\d)(张|条|个)(图片|记录|附件|孕期)?/g,'$1 $2$3').replace(/^✓\s*/,'').replace(/^＋\s*/,'').replace(/^‹ /,'返回 · ').replace(/^‹返回/,'返回 · ');
if(value!==n.characters){n.characters=value;mutated.push(n.id);}
}
}
const detail=await figma.getNodeByIdAsync('12:497');
for(const row of detail.findAll(n=>n.type==='FRAME'&&['资料类型','来源医院','附件数量'].includes(n.name))){const values=row.children.filter(n=>n.type==='TEXT').map(n=>n.characters);for(const n of [...row.children]){removed.push(n.id);n.remove();}row.layoutMode='HORIZONTAL';row.itemSpacing=16;row.primaryAxisSizingMode='FIXED';row.counterAxisSizingMode='AUTO';row.resize(324,24);const a=tx(row,values[0],'label','muted'),b=tx(row,values[1],'label','ink');a.textAutoResize='HEIGHT';a.resize(96,21);b.textAutoResize='HEIGHT';b.resize(212,21);mutated.push(row.id);}
const health=await figma.getNodeByIdAsync('14:408');const chart=health.findOne(n=>n.type==='FRAME'&&n.name==='Chart · demo data'),axis=health.findOne(n=>n.type==='FRAME'&&n.name==='Chart axis');
for(const n of [...chart.children]){removed.push(n.id);n.remove();}chart.name='Chart · kg · sample data';chart.layoutMode='NONE';chart.resize(324,104);
const data=[{day:1,kg:57.1},{day:4,kg:57.4},{day:7,kg:57.3},{day:10,kg:57.6},{day:12,kg:57.8},{day:14,kg:57.9},{day:17,kg:58.2}];
function x(day){return 40+(day-1)/16*276;}function y(kg){return 8+(58.5-kg)/1.5*88;}
for(const kg of [58.5,58.0,57.5,57.0]){const label=tx(chart,kg.toFixed(1),'caption','muted');label.x=0;label.y=y(kg)-8;const ln=figma.createVector();chart.appendChild(ln);ln.vectorPaths=[{windingRule:'NONZERO',data:'M40 '+y(kg)+' L316 '+y(kg)}];ln.strokes=[paint('line')];ln.strokeWeight=1;created.push(ln.id);}
const path=figma.createVector();chart.appendChild(path);path.vectorPaths=[{windingRule:'NONZERO',data:data.map((d,i)=>(i?'L':'M')+x(d.day)+' '+y(d.kg)).join(' ')}];path.strokes=[paint('primary')];path.strokeWeight=2;path.fills=[];created.push(path.id);
for(const d of data){const dot=figma.createEllipse();chart.appendChild(dot);dot.resize(5,5);dot.x=x(d.day)-2.5;dot.y=y(d.kg)-2.5;fill(dot,'primary');created.push(dot.id);}
for(const n of [...axis.children]){removed.push(n.id);n.remove();}axis.layoutMode='NONE';axis.resize(324,18);for(const [str,xx] of [['10/01',40],['10/09',x(9)-16],['10/17',284]]){const n=tx(axis,str);n.x=xx;n.y=0;}
mutated.push(chart.id,axis.id);
const hint=health.findOne(n=>n.type==='TEXT'&&n.characters==='趋势仅用于回顾记录，不进行健康判断。');if(hint){hint.characters='体重（kg）· 示例数据；刻度不代表健康阈值。';mutated.push(hint.id);}
return {createdNodeIds:created,mutatedNodeIds:[...new Set(mutated)],removedNodeIds:removed,icons:Object.fromEntries(Object.entries(icons).map(([k,n])=>[k,n.id])),rowProperties:{icon:iconProp,chevron:showChevron},screenCount:screens.length,rows:rowInfo.length,chartData:data};

