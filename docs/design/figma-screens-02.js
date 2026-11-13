
const b3=board('03 · 用药与健康',3500),r3=screenRow(b3);const screens=[];
let s=screen(r3,'09 Medication','用药记录','记录医嘱与实际服药情况',2);screens.push(s);
const mt=box(s.body,'Medication tabs',364,'HORIZONTAL',null,0,12);badge(mt,'当前方案');badge(mt,'服药历史');
const med=card(s.body,'Medication plan');badge(med,'进行中');text(med,'示例用药方案','heading');text(med,'剂量与频次按医生医嘱填写','body','muted',324);line(med);text(med,'每日 08:00、20:00','heading');text(med,'起始 10月1日 · 医嘱原文已保存','caption','muted');text(med,'本页为演示，不构成用药建议。','caption','muted');
label(s.body,'今天的记录');await row(s.body,'08:00 · 待确认','未记录不代表未服用','＋');await button(s.body,'记录已服用');await button(s.body,'稍后提醒','Secondary');await row(s.body,'20:00 · 待到时间','可在实际服用后补充记录','◷');text(s.body,'方案调整保留历史，既往服药记录不变。','caption','muted',364);
s=screen(r3,'10 MedicationEditor','记录用药方案','‹ 返回用药  ·  请按实际医嘱填写');screens.push(s);
await field(s.body,'药品名称 *','填写药品名称');await field(s.body,'规格（选填）','按包装填写');await field(s.body,'每次剂量与单位','填写医嘱剂量');await field(s.body,'频次','每天固定时刻');await field(s.body,'服药时间','08:00、20:00');await field(s.body,'起止日期','选择开始和结束日期');
const mi=card(s.body,'Medication instructions','soft');text(mi,'医嘱复杂时，可以只保存原文','label');text(mi,'纯文本方案不自动生成提醒，也不自动推断剂量。','body','muted',324);
await button(s.body,'保存用药方案');
s=screen(r3,'11 Health','健康记录','看见变化，也保留每一次原始记录',2);screens.push(s);
const ht=box(s.body,'Metric filters',364,'HORIZONTAL',null,0,8);for(const n of ['体重','血压','血糖'])badge(ht,n);
const hc=card(s.body,'Weight trend','surface');text(hc,'最近一次体重','label','muted');text(hc,'58.2 kg','display');text(hc,'10月17日 07:30 · 手动记录','caption','muted');
const chart=box(hc,'Chart · demo data',324,'HORIZONTAL',null,0,16);chart.resize(324,104);chart.counterAxisSizingMode='FIXED';chart.counterAxisAlignItems='MAX';for(const h of [34,48,40,63,77,83,92]){const bar=figma.createRectangle();chart.appendChild(bar);bar.resize(29,h);bar.cornerRadius=8;fill(bar,'soft');ids.push(bar.id);}const axis=box(hc,'Chart axis',324,'HORIZONTAL',null,0,110);text(axis,'10/01','caption','muted');text(axis,'10/17','caption','muted');
text(hc,'趋势仅用于回顾记录，不进行健康判断。','caption','muted',324);label(s.body,'历史记录','查看全部');await row(s.body,'58.2 kg','10月17日 07:30 · 体重','＋');await row(s.body,'57.9 kg','10月14日 07:40 · 体重','＋');await button(s.body,'＋ 记录健康指标');
s=screen(r3,'12 Timeline','孕期时间线','把一次次记录，连成自己的故事',2);screens.push(s);
const tt=box(s.body,'Timeline filters',364,'HORIZONTAL',null,0,12);badge(tt,'全部');badge(tt,'产检');badge(tt,'日常');
label(s.body,'10月17日','孕18周+3天');await row(s.body,'记录体重 · 58.2 kg','07:30 · 健康记录','＋');
const jc=card(s.body,'Journal note','apricot');text(jc,'今天想记下的小事','heading');text(jc,'散步时天气很好。想把这些平常又特别的日子，也一起留下来。','body','ink',324);text(jc,'日常备注 · 只有日期，没有具体时间','caption','amber');
label(s.body,'10月14日','孕18周');await row(s.body,'完成一次产检','常规产检 · 市妇幼保健院','✓');await row(s.body,'新增超声检查报告','3张图片 · 已关联本次产检','▤');await button(s.body,'＋ 写一条日常备注');
const b4=board('04 · 安全与备份',4700),r4=screenRow(b4);
s=screen(r4,'13 Settings','我的','让资料安全，也让你放心',3);screens.push(s);
const profile=card(s.body,'Active pregnancy','soft');text(profile,'我的孕期','title');text(profile,'孕18周+3天 · 进行中','body','primary');text(profile,'管理档案与日期依据  ›','label','primary');
await row(s.body,'备份与恢复','最近完整备份：今天 08:32','✓');await row(s.body,'应用锁与隐私','已开启 · 离开应用后自动锁定','✓');await row(s.body,'提醒设置','产检提醒、用药提醒与权限','◷');await row(s.body,'导出我的资料','就诊摘要、原件与完整备份','▤');await row(s.body,'回收站','已删除资料保留30天','↶');text(s.body,'孕迹 V1.0 · 本地优先 · 数据由你掌握','caption','muted',364);
s=screen(r4,'14 Backup','备份与恢复','‹ 返回我的');screens.push(s);
const bc=card(s.body,'Backup complete','soft');icon(bc,'shield','primary',40);text(bc,'资料已完整备份','title');text(bc,'今天 08:32 · 坚果云','body','primary');text(bc,'1 个孕期 · 28 条记录 · 12 个附件','caption','muted');badge(bc,'所有附件校验通过');
await row(s.body,'当前备份设备','此手机 · 主设备','✓');await row(s.body,'备份设置','自动备份 · 仅 Wi-Fi 上传附件','◷');await row(s.body,'恢复密钥','已确认保存 · 请与备份分开保管','✓');await row(s.body,'历史快照','查看或恢复以往完整备份','▤');await button(s.body,'立即备份');await button(s.body,'从备份恢复','Secondary');text(s.body,'V1.0 为单主设备备份；恢复后不会自动双向同步。','caption','muted',364);
s=screen(r4,'15 RestorePreview','恢复资料','‹ 返回  ·  第3步，共4步');screens.push(s);
badge(s.body,'已完成完整性校验');text(s.body,'准备好接回\n你的孕期记录','title','ink',364);
const rs=card(s.body,'Restore summary');for(const [name,value] of [['备份日期','2026年10月17日 08:32'],['来源设备','原主设备'],['孕期档案','1 个'],['业务记录','28 条'],['原始附件','12 个 · 全部通过校验']]){const z=box(rs,name,324,'HORIZONTAL',null,0,12);z.primaryAxisAlignItems='SPACE_BETWEEN';text(z,name,'label','muted');text(z,value,'label');}
const rw=card(s.body,'Restore explanation','apricot');text(rw,'恢复将替换本机现有记录','label','amber');text(rw,'替换前会先保存本机安全快照。此次恢复不合并两份资料，自动云端备份保持关闭。','body','ink',324);
await button(s.body,'确认恢复');await button(s.body,'暂不恢复','Secondary');
s=screen(r4,'16 BackupError','备份暂未完成','‹ 返回备份');screens.push(s);
const err=card(s.body,'Backup failure','apricot');icon(err,'shield','amber',40);text(err,'本机资料已保存','title');text(err,'云端连接需要重新验证','heading','amber');text(err,'记录和报告仍可在本机查看。更新云盘凭据后，可以继续备份。','body','ink',324);
await row(s.body,'待备份内容','3 条新记录 · 2 个附件','◷');await row(s.body,'最近完整备份','昨天 21:10 · 已校验','✓');await button(s.body,'更新云盘凭据');await button(s.body,'导出本地加密备份','Secondary');text(s.body,'请保留本机资料，完成备份前不要清除应用数据。','caption','muted',364);
return result([b3,b4],screens);

