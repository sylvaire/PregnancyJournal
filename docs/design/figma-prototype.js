
await figma.setCurrentPageAsync(figma.root.children[0]);const p=figma.currentPage,mutated=[],links=[];
const screens=[];for(const id of ["12:26","12:68","12:138","12:217","12:283","12:333","12:409","12:497","14:255","14:329","14:408","14:485","14:564","14:635","14:694","14:738","16:472","16:521","16:576","16:644"])screens.push(await figma.getNodeByIdAsync(id));
for(const n of p.findAll(n=>n.type==='FRAME'&&n.name==='Section')){const t=n.children[0];if(t&&t.type==='TEXT'){await figma.loadFontAsync(t.fontName);t.textAutoResize='HEIGHT';t.resize(250,t.height);t.layoutSizingHorizontal='FILL';mutated.push(t.id);}}
const fieldMaster=await figma.getNodeByIdAsync('7:2485');fieldMaster.clipsContent=false;mutated.push(fieldMaster.id);
for(const s of screens){const body=s.children.find(n=>n.name==='Content');if(body.children[0]?.type==='INSTANCE'&&body.children[0].name.startsWith('Field ·')){body.paddingTop=8;mutated.push(body.id);}
for(const f of s.findAll(n=>n.type==='INSTANCE'&&n.name.startsWith('Field ·'))){f.clipsContent=false;mutated.push(f.id);for(const n of f.findAll(n=>n.type==='FRAME')){n.clipsContent=false;mutated.push(n.id);}}
}
for(const r of p.findAll(n=>n.type==='FRAME'&&['资料类型','来源医院','附件数量'].includes(n.name))){const t=r.children[0];if(t&&t.type==='TEXT'){await figma.loadFontAsync(t.fontName);t.textAutoResize='HEIGHT';t.resize(160,t.height);t.layoutSizingHorizontal='FILL';mutated.push(t.id);}}
const byNum=Object.fromEntries(screens.map(n=>[n.name.slice(0,2),n]));
async function link(n,target){await n.setReactionsAsync([{trigger:{type:'ON_CLICK'},actions:[{type:'NODE',destinationId:byNum[target].id,navigation:'NAVIGATE',transition:null}]}]);mutated.push(n.id);links.push({from:n.id,to:byNum[target].id,label:n.name});}
const navTargets={'今天':'03','产检':'04','记录':'07','我的':'13'};
for(const s of screens)for(const n of s.findAll(n=>n.type==='FRAME'&&n.name.startsWith('Nav · ')))await link(n,navTargets[n.name.replace('Nav · ','')]);
const rules={
'01':[['Button · 创建我的孕期档案','02'],['Button · 恢复已有资料','20']],
'02':[['Button · 保存并进入首页','03'],['Button · 日期暂不确定，先建档','03']],
'03':[['Record · 08:00 · 用药待确认','09'],['Record · 下一次产检 · 10月21日','05'],['Record · 最近资料 · 超声检查','08'],['检查资料','07'],['健康指标','11'],['日常备注','12']],
'04':[['Upcoming visit','05'],['Button · ＋ 新建产检预约','06']],
'05':[['Button · 修改预约','06'],['Record · 暂未添加本次报告','17']],
'06':[['Button · 保存预约','04']],
'07':[['Record · 超声检查报告','08'],['Button · ＋ 保存检查资料','17']],
'09':[['Medication plan','10']],
'10':[['Button · 保存用药方案','09']],
'12':[['Record · 新增超声检查报告','08'],['Record · 完成一次产检','05']],
'13':[['Record · 备份与恢复','14']],
'14':[['Button · 从备份恢复','20'],['Record · 当前备份设备','19']],
'15':[['Button · 暂不恢复','14']],
'16':[['Button · 更新云盘凭据','19']],
'17':[['Button · 拍照保存','18'],['Button · 选择图片或 PDF','18']],
'18':[['Button · 暂时返回列表','07']],
'19':[['Button · 暂不开启备份','13']],
'20':[['Button · 验证并检查备份','15'],['Button · 重新选择备份文件','14']]
};
for(const [num,rs] of Object.entries(rules))for(const [name,dest] of rs){const n=byNum[num].findOne(n=>n.name===name);if(n)await link(n,dest);}
const recordSections=byNum['07'].findOne(n=>n.name==='Record sections');if(recordSections)for(const n of recordSections.children){const t=n.findOne(x=>x.type==='TEXT');if(t&&{'用药':'09','健康':'11','时间线':'12'}[t.characters])await link(n,{'用药':'09','健康':'11','时间线':'12'}[t.characters]);}
p.flowStartingPoints=[{nodeId:byNum['01'].id,name:'首次使用'},{nodeId:byNum['03'].id,name:'日常记录'},{nodeId:byNum['14'].id,name:'备份与恢复'}];mutated.push(p.id);
return {mutatedNodeIds:[...new Set(mutated)],links,flowStartingPoints:p.flowStartingPoints,checks:{screens:screens.length,links:links.length}};

