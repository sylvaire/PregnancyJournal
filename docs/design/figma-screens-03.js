
const b5=board('05 · 关键状态与连接',5900),r5=screenRow(b5);const screens=[];
let s=screen(r5,'17 EmptyDocuments','检查资料','每一份原件，都值得好好保存',2);screens.push(s);
const empty=card(s.body,'Empty state','soft');empty.paddingTop=32;empty.paddingBottom=32;icon(empty,'file','primary',56);text(empty,'第一份资料\n从这里开始','title');text(empty,'拍照、选择图片或导入 PDF，\n先保存，再慢慢整理。','body','muted',324);await button(s.body,'拍照保存');await button(s.body,'选择图片或 PDF','Secondary');text(s.body,'只读取你主动选择的文件，导入后保存加密副本。','caption','muted',364);
s=screen(r5,'18 ImportProgress','保存检查资料','‹ 返回资料  ·  正在导入');screens.push(s);
const im=card(s.body,'Import status','soft');icon(im,'file','primary',40);text(im,'正在保存到本机','title');text(im,'已完成 2 / 3 个文件','body','primary');const pb=box(im,'Import progress',324,'HORIZONTAL','line',0,0);pb.resize(324,8);pb.counterAxisSizingMode='FIXED';const pp=figma.createRectangle();pb.appendChild(pp);pp.resize(216,8);fill(pp,'primary');ids.push(pp.id);
await row(s.body,'检查图片 01','已加密保存','✓');await row(s.body,'检查图片 02','已加密保存','✓');await row(s.body,'检查报告 PDF','正在处理 · 请稍候','◷');await field(s.body,'资料标题','10月14日检查资料');text(s.body,'文件全部保存后才会标记导入完成；云端备份随后进行。','body','muted',364);await button(s.body,'暂时返回列表','Secondary');
s=screen(r5,'19 CloudConnect','连接坚果云','‹ 返回备份  ·  可选设置');screens.push(s);
const ci=card(s.body,'Cloud explanation','soft');icon(ci,'shield');text(ci,'加密后，再交给云盘','heading');text(ci,'云端保存加密副本。请单独保管恢复密钥，换机时需要使用。','body','muted',324);
await field(s.body,'WebDAV 服务地址','填写 HTTPS 服务地址');await field(s.body,'账号','填写坚果云账号');await field(s.body,'应用密码','••••••••••••');await field(s.body,'备份目录','选择专用目录');await button(s.body,'测试连接');await button(s.body,'暂不开启备份','Secondary');text(s.body,'凭据只保存在本机，不写入报告或资料备份。','caption','muted',364);
s=screen(r5,'20 RecoveryKey','解锁备份','‹ 返回  ·  第2步，共4步');screens.push(s);
badge(s.body,'已找到加密备份');text(s.body,'输入你的\n恢复密钥','title','ink',364);
const ki=card(s.body,'Recovery key explanation','soft');icon(ki,'shield','primary',40);text(ki,'只有你能解开这份资料','heading');text(ki,'请使用开启备份时保存的恢复密钥。它与云盘登录密码不同。','body','muted',324);
await field(s.body,'恢复密钥','粘贴或手动输入恢复密钥');await button(s.body,'验证并检查备份');await button(s.body,'重新选择备份文件','Secondary');text(s.body,'密钥仅用于本机解密，不会发送给云盘。','caption','muted',364);
return result([b5],screens);

