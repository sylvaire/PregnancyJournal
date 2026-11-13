
await figma.setCurrentPageAsync(figma.root.children[0]);const p=figma.currentPage,created=[],mutated=[],removed=[];
const buttons=p.children.filter(n=>n.type==='FRAME'&&n.children.some(c=>c.name==='App bar')).flatMap(s=>s.findAll(n=>n.type==='INSTANCE'&&n.name.startsWith('Button ·')));
const values=buttons.map(n=>({id:n.id,label:n.findOne(x=>x.type==='TEXT').characters}));
for(const b of buttons)for(const t of b.findAll(n=>n.type==='TEXT'))for(const seg of t.getStyledTextSegments(['fontName']))await figma.loadFontAsync(seg.fontName);
await figma.loadFontAsync({family:'Noto Sans SC',style:'Medium'});
const variables=Object.fromEntries((await figma.variables.getLocalVariablesAsync()).map(v=>[v.name,v]));
function paint(c){return figma.variables.setBoundVariableForPaint({type:'SOLID',color:{r:0,g:0,b:0}},'color',variables['color/'+c]);}
const set=await figma.getNodeByIdAsync('7:2484');const prop=set.addComponentProperty('Label','TEXT','保存记录');
for(const id of ['7:2462','7:2470','7:2477']){const c=await figma.getNodeByIdAsync(id);for(const n of [...c.children]){removed.push(n.id);n.remove();}c.layoutMode='HORIZONTAL';c.primaryAxisSizingMode='FIXED';c.counterAxisSizingMode='FIXED';c.resize(364,52);c.primaryAxisAlignItems='CENTER';c.counterAxisAlignItems='CENTER';c.paddingTop=c.paddingBottom=0;c.paddingLeft=c.paddingRight=20;c.itemSpacing=0;c.cornerRadius=26;c.fills=[paint(id==='7:2462'?'primary':id==='7:2470'?'soft':'line')];c.clipsContent=false;
const t=figma.createText();c.appendChild(t);t.fontName={family:'Noto Sans SC',style:'Medium'};t.fontSize=16;t.lineHeight={unit:'PIXELS',value:24};t.characters=id==='7:2477'?'请先完成必填项':'保存记录';t.name='Label';t.fills=[paint(id==='7:2462'?'surface':id==='7:2470'?'primary':'muted')];t.componentPropertyReferences={characters:prop};t.textAutoResize='WIDTH_AND_HEIGHT';created.push(t.id);mutated.push(c.id);}
set.description='孕迹按钮：364 × 52，圆角26；Primary/Secondary/Disabled；Label 为文本属性。由 Material 3 规格整理为原生品牌组件，消除嵌套尺寸约束。';mutated.push(set.id);
for(const info of values){const b=await figma.getNodeByIdAsync(info.id);b.setProperties({[prop]:info.label});b.resize(364,52);b.primaryAxisSizingMode='FIXED';b.counterAxisSizingMode='FIXED';mutated.push(b.id);}
const screens=p.children.filter(n=>n.type==='FRAME'&&n.children.some(c=>c.name==='App bar'));
for(const s of screens)for(const t of s.findAll(n=>n.type==='TEXT')){for(const seg of t.getStyledTextSegments(['fontName']))await figma.loadFontAsync(seg.fontName);const value=t.characters.replace(/^返回 · 返回/,'返回').replace(/^返回 · 第/,'返回 · 第').replace('第3步，共4步','第 3 步，共 4 步').replace('第2步，共4步','第 2 步，共 4 步');if(value!==t.characters){t.characters=value;mutated.push(t.id);}}
const med=await figma.getNodeByIdAsync('14:255');const card=med.findOne(n=>n.name==='Medication plan');card.itemSpacing=8;mutated.push(card.id);
return {createdNodeIds:created,mutatedNodeIds:mutated,removedNodeIds:removed,labelProperty:prop,buttonCount:values.length,labels:values};

