
const b1=board('01 · 开始与日常',1100);const r1=screenRow(b1);const screens=[];
let s=screen(r1,'01 Welcome','孕迹','把每一段变化，好好留存');screens.push(s);
const hero=card(s.body,'Welcome hero','soft');hero.paddingTop=32;hero.paddingBottom=32;icon(hero,'leaf','primary',64);text(hero,'轻轻记录\n安心留存','display','primary',324);text(hero,'产检、报告与日常变化，\n在这里慢慢连成你的孕期故事。','body','ink',324);
await row(s.body,'资料随身，离线也可查看','报告原件归档 · 本机加密保存','✓');
await row(s.body,'换一台手机，也能带走回忆','由你选择是否开启加密备份','✓');
await button(s.body,'创建我的孕期档案');await button(s.body,'恢复已有资料','Secondary');
text(s.body,'无需注册账号 · 连接云盘为可选项','caption','muted');
s=screen(r1,'02 CreatePregnancy','创建孕期档案','先从一个名字开始，日期也可以稍后补充');screens.push(s);
await field(s.body,'档案名称','我的孕期');label(s.body,'选择日期依据','可稍后填写');
await field(s.body,'医生确认的预产期','2027年3月17日');await field(s.body,'末次月经日期（选填）','2026年6月10日');
const info=card(s.body,'Date basis','soft');icon(info,'calendar');text(info,'已填写医生确认的预产期','label');text(info,'首页将以此计算孕周。后续修改会保留原始日期依据。','body','muted',324);
await field(s.body,'建档医院（选填）','填写医院名称');await button(s.body,'保存并进入首页');await button(s.body,'日期暂不确定，先建档','Secondary');
s=screen(r1,'03 Today','今天，慢慢来','10月17日 星期六  ·  我的孕期 ⌄',0);screens.push(s);
const preg=card(s.body,'Pregnancy summary','soft');const pr=box(preg,'Eyebrow',324,'HORIZONTAL',null,0,12);badge(pr,'孕中期');text(preg,'18 周 + 3 天','display','primary');text(preg,'预产期 2027年3月17日 · 医生确认','caption','muted');
const progress=box(preg,'Pregnancy progress',324,'HORIZONTAL','line',0,0);progress.resize(324,6);progress.primaryAxisSizingMode='FIXED';radius(progress,12);const seg=figma.createRectangle();progress.appendChild(seg);seg.resize(149,6);fill(seg,'primary');ids.push(seg.id);
label(s.body,'今天的安排','查看全部');await row(s.body,'08:00 · 用药待确认','按已保存的方案记录服药情况','＋');
await row(s.body,'下一次产检 · 10月21日','09:00 · 市妇幼保健院','21');
label(s.body,'随手记一笔');const quick=box(s.body,'Quick actions',364,'HORIZONTAL',null,0,12);for(const [name,key] of [['检查资料','file'],['健康指标','plus'],['日常备注','leaf']]){const q=box(quick,name,113,'VERTICAL','surface',12,8);radius(q,20);icon(q,key);text(q,name,'label');}
await row(s.body,'最近资料 · 超声检查','10月14日 · 已保存到本机','✓');text(s.body,'✓ 最近完整备份：今天 08:32','caption','primary');
s=screen(r1,'04 Visits','产检','每次预约与检查，都有迹可循',1);screens.push(s);
const tabs=box(s.body,'Visit tabs',364,'HORIZONTAL',null,0,12);badge(tabs,'待进行  2');badge(tabs,'历史记录');label(s.body,'2026年10月','日历视图');
const visit=card(s.body,'Upcoming visit');badge(visit,'4 天后');text(visit,'10月21日  周三','heading');text(visit,'09:00 · 常规产检','title');text(visit,'市妇幼保健院 · 产科门诊','body','muted',324);line(visit);text(visit,'准备事项：携带既往报告与就诊卡','label','muted',324);text(visit,'提醒：前一天 18:00、当天 07:30','caption','primary');
await row(s.body,'11月4日 · 复诊','时间待定 · 可稍后补充','04');await button(s.body,'＋ 新建产检预约');text(s.body,'过期预约会保留，等待你确认就诊情况。','caption','muted',364);
const b2=board('02 · 产检与资料',2300);const r2=screenRow(b2);
s=screen(r2,'05 VisitDetail','产检详情','‹ 返回产检');screens.push(s);
const vd=card(s.body,'Visit overview','soft');badge(vd,'待进行');text(vd,'常规产检','title');text(vd,'10月21日 09:00 · 孕19周','body');text(vd,'市妇幼保健院 · 产科门诊','caption','muted');label(s.body,'检查项目');await row(s.body,'常规检查、超声检查','项目内容由你按预约信息填写','✓');label(s.body,'准备事项');text(s.body,'携带就诊卡、既往报告。其他准备要求以医院通知为准。','body','muted',364);label(s.body,'关联资料','＋ 添加');await row(s.body,'暂未添加本次报告','拍照或导入后，可直接关联','＋');await button(s.body,'记录本次就诊');await button(s.body,'修改预约','Secondary');
s=screen(r2,'06 VisitEditor','新建产检预约','‹ 返回  ·  未完成内容会保留草稿');screens.push(s);
await field(s.body,'预约日期 *','2026年10月21日');await field(s.body,'预约时间（选填）','09:00');await field(s.body,'医院','市妇幼保健院');await field(s.body,'检查项目','常规检查、超声检查');await field(s.body,'准备事项','按医院要求填写');label(s.body,'提醒设置');await row(s.body,'前一天 18:00','通知中默认隐藏医院和项目','✓');await button(s.body,'保存预约');text(s.body,'时间不确定时可留空，提醒时刻单独设置。','caption','muted',364);
s=screen(r2,'07 Documents','我的记录','把重要资料，放在一起',2);screens.push(s);
const docTabs=box(s.body,'Record sections',364,'HORIZONTAL',null,0,8);for(const name of ['资料','用药','健康','时间线'])badge(docTabs,name);
await field(s.body,'搜索资料','输入标题或备注');label(s.body,'全部资料','按日期筛选');
await row(s.body,'超声检查报告','10月14日 · 超声 · 3张图片','▤');await row(s.body,'血常规检查','10月14日 · 检验 · PDF','▤');await row(s.body,'门诊处方','10月7日 · 处方 · 1张图片','▤');await row(s.body,'首次建档记录','9月9日 · 病历 · PDF','▤');await button(s.body,'＋ 保存检查资料');text(s.body,'资料已加密保存在本机，可离线查看。','caption','primary',364);
s=screen(r2,'08 DocumentDetail','检查资料','‹ 返回资料  ·  编辑');screens.push(s);
const preview=card(s.body,'Editable document illustration','surface');preview.paddingTop=24;preview.paddingBottom=24;badge(preview,'示例资料 · 非真实报告');text(preview,'超声检查报告','heading');text(preview,'2026年10月14日','caption','muted');line(preview);for(const [a,b] of [['资料类型','超声'],['来源医院','市妇幼保健院'],['附件数量','3 张图片']]){const x=box(preview,a,324,'HORIZONTAL',null,0,8);x.primaryAxisAlignItems='SPACE_BETWEEN';text(x,a,'label','muted');text(x,b,'label');}text(preview,'此区域表示报告原件预览入口，\n点击后进入图片或 PDF 阅读页。','body','muted',324);
await row(s.body,'关联产检 · 10月14日','本次资料可从产检详情直接查看','✓');label(s.body,'我的备注');text(s.body,'下次就诊时带上原始报告。','body','muted',364);await button(s.body,'查看原件');await button(s.body,'导出或分享','Secondary');text(s.body,'✓ 已保存到本机  ·  等待下一次备份','caption','primary');
return result([b1,b2],screens);

