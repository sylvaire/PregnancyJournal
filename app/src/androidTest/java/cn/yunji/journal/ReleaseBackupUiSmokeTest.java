package cn.yunji.journal;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.util.AtomicFile;
import android.util.Base64;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipFile;
import javax.crypto.*;
import javax.crypto.spec.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** No app/Kotlin API calls: this can validate the genuinely shrunk/obfuscated APK. Emulator only. */
@LargeTest
public class ReleaseBackupUiSmokeTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private interface Match {boolean test(AccessibilityNodeInfo node);}
    private List<AccessibilityNodeInfo> nodes() {
        List<AccessibilityNodeInfo> result=new ArrayList<>();
        AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();
        if(root==null)return result;
        ArrayDeque<AccessibilityNodeInfo> queue=new ArrayDeque<>();queue.add(root);
        while(!queue.isEmpty() && result.size()<5000){AccessibilityNodeInfo n=queue.removeLast();result.add(n);for(int i=n.getChildCount()-1;i>=0;i--){AccessibilityNodeInfo child=n.getChild(i);if(child!=null)queue.add(child);}}
        return result;
    }
    private AccessibilityNodeInfo find(Match match){for(AccessibilityNodeInfo n:nodes())if(n.isVisibleToUser() && match.test(n))return n;return null;}
    private AccessibilityNodeInfo await(String step,Match match) {
        long end=SystemClock.elapsedRealtime()+20000;
        while(SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo n=find(match);if(n!=null)return n;SystemClock.sleep(100);}
        throw new AssertionError("Release validation step missing: "+step);
    }
    private static String text(CharSequence value){return value==null?"":value.toString();}
    private void click(AccessibilityNodeInfo node){for(int i=0;i<8 && node!=null;i++,node=node.getParent())if(node.isEnabled() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;throw new AssertionError("Release control not clickable");}
    private void clickText(String label) {
        long end=SystemClock.elapsedRealtime()+20000,last=0;
        while(SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo n=find(v->text(v.getText()).equals(label));if(n!=null){AccessibilityNodeInfo candidate=n;for(int i=0;i<8 && candidate!=null;i++,candidate=candidate.getParent())if(candidate.isEnabled() && candidate.isClickable() && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;}if(SystemClock.elapsedRealtime()-last>600){n=find(AccessibilityNodeInfo::isScrollable);if(n!=null)n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);last=SystemClock.elapsedRealtime();}SystemClock.sleep(100);}
        throw new AssertionError("Release entry missing: "+label);
    }
    private static final Pattern GROUP=Pattern.compile("第([1-8])组");
    private Map<Integer,String> captureSyntheticKeyGroups() {
        Map<Integer,String> groups=new TreeMap<>();
        for(int attempt=0;attempt<12;attempt++){
            Integer index=null;
            for(AccessibilityNodeInfo n:nodes()){
                String value=text(n.getText());Matcher match=GROUP.matcher(value);
                if(match.matches())index=Integer.parseInt(match.group(1));
                else if(index!=null && value.matches("[A-Za-z0-9_-]{6}")){groups.put(index,value);index=null;}
            }
            if(groups.size()==8)return groups;
            AccessibilityNodeInfo scroll=find(AccessibilityNodeInfo::isScrollable);if(scroll!=null)scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);SystemClock.sleep(150);
        }
        throw new AssertionError("Synthetic key groups unavailable");
    }
    private JSONObject handoff(Context context) throws Exception {
        AtomicFile file=new AtomicFile(new File(context.getNoBackupFilesDir(),"file-handoff.enc"));
        if(!file.getBaseFile().exists())return null;
        byte[] bytes;try(InputStream in=file.openRead()){bytes=read(in,200000);}
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        SecretKey key=(SecretKey)store.getKey(context.getPackageName()+".file-handoff.v1",null);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(bytes,0,12)));cipher.updateAAD("file-handoff/v1".getBytes(StandardCharsets.UTF_8));
        byte[] plain=cipher.doFinal(bytes,12,bytes.length-12);
        try{return new JSONObject(new String(plain,StandardCharsets.UTF_8));}finally{Arrays.fill(plain,(byte)0);}
    }
    private static byte[] read(InputStream in,int maximum) throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1){if(out.size()+count>maximum)throw new IOException("Validation input exceeds limit");out.write(buffer,0,count);}return out.toByteArray();}
    private static byte[] decode(String value){return Base64.decode(value,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
    private static byte[] derive(byte[] master,String vault,String purpose) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(vault.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));byte[] prk=mac.doFinal(master);
        try{mac.init(new SecretKeySpec(prk,"HmacSHA256"));mac.update(("pregnancy-journal/v1/"+purpose).getBytes(StandardCharsets.UTF_8));return mac.doFinal(new byte[]{1});}finally{Arrays.fill(prk,(byte)0);}
    }
    private static byte[] decrypt(byte[] key,byte[] encrypted,String aad) throws Exception {
        assertTrue(encrypted.length>=32 && encrypted[0]==0x50 && encrypted[1]==0x4a && encrypted[2]==0x45 && encrypted[3]==1);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(encrypted,4,16)));cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));return cipher.doFinal(encrypted,16,encrypted.length-16);
    }
    private void independentRecoveryCheck(File archive,Map<Integer,String> groups) throws Exception {
        StringBuilder encoded=new StringBuilder();for(int i=1;i<=8;i++)encoded.append(groups.get(i));
        byte[] recovery=decode(encoded.toString()),raw=Arrays.copyOf(recovery,32),master=null,wrapKey=null,manifestKey=null,plain=null;
        try(ZipFile zip=new ZipFile(archive)){
            assertEquals(36,recovery.length);assertTrue(MessageDigest.isEqual(Arrays.copyOfRange(recovery,32,36),Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(raw),4)));
            JSONObject header;try(InputStream in=zip.getInputStream(zip.getEntry("vault.json"))){header=new JSONObject(new String(read(in,4096),StandardCharsets.UTF_8));}
            assertEquals("YJ-RECOVERY-1",header.getString("format"));String vault=header.getString("vaultId"),slot=header.getString("slotId");
            assertEquals("HKDF-SHA256-AES256-GCM",header.getString("algorithm"));
            wrapKey=derive(raw,vault,"recovery-slot/"+slot);
            master=decrypt(wrapKey,decode(header.getString("wrappedMaster")),"recovery/v1/"+vault+"/"+slot+"/HKDF-SHA256-AES256-GCM/master");assertEquals(32,master.length);
            manifestKey=derive(master,vault,"backup-manifest");byte[] manifest;try(InputStream in=zip.getInputStream(zip.getEntry("manifest.enc"))){manifest=read(in,16*1024*1024+32);}
            plain=decrypt(manifestKey,manifest,"backup/v1/"+vault+"/manifest");JSONObject snapshot=new JSONObject(new String(plain,StandardCharsets.UTF_8));assertTrue(vault.equals(snapshot.getString("vaultId")));assertEquals(1,snapshot.getInt("formatVersion"));
            for(String table:new String[]{"pregnancies","dateRevisions","visits","attachments","deletionBatches","medications","plans","occurrences","intakes","definitions","measurements","values","entries"})assertNotNull(snapshot.getJSONArray(table));
        }finally{for(byte[] value:new byte[][]{recovery,raw,master,wrapKey,manifestKey,plain})if(value!=null)Arrays.fill(value,(byte)0);}
    }
    @Test public void genuinelyObfuscatedReleaseConfirmsKeySavesAndIndependentlyRecoversManifest() throws Exception {
        assumeTrue(Build.MODEL.equals("sdk_gphone64_x86_64") && Build.VERSION.SDK_INT==34);
        Context context=instrumentation.getTargetContext();assertEquals(0,context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE);
        boolean originalClassExists;try{Class.forName("cn.yunji.journal.core.storage.FileGcm");originalClassExists=true;}catch(ClassNotFoundException expected){originalClassExists=false;}assertFalse(originalClassExists);
        assumeTrue(!context.getSharedPreferences("security-state",Context.MODE_PRIVATE).getBoolean("lock-enabled",false));
        File device=new File(context.getNoBackupFilesDir(),"vault/device-key.json");try(InputStream in=new FileInputStream(device)){assumeTrue(!new JSONObject(new String(read(in,16384),StandardCharsets.UTF_8)).optString("mode","NORMAL").equals("STRICT"));}
        AtomicFile settings=new AtomicFile(new File(context.getNoBackupFilesDir(),"vault/recovery-slot.enc"));assumeTrue(!new File(settings.getBaseFile()+".bak").exists());assumeTrue(handoff(context)==null);
        byte[] previous=null;if(settings.getBaseFile().exists())try(InputStream in=settings.openRead()){previous=read(in,8192);}
        Activity activity=null;Uri uri=null;String requestId=null;Map<Integer,String> groups=new TreeMap<>();File archive=new File(context.getCacheDir(),"release-validation-"+UUID.randomUUID()+".yunji");
        try {
            settings.delete();activity=instrumentation.startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(context.getPackageName(),"cn.yunji.journal.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            clickText("我的");clickText("本地备份");clickText("生成恢复密钥");await("synthetic key",n->text(n.getText()).equals("YJ1:"));groups.putAll(captureSyntheticKeyGroups());clickText("已保存，开始核对");
            List<Integer> requested=new ArrayList<>();List<AccessibilityNodeInfo> inputs=new ArrayList<>();
            long verificationDeadline=SystemClock.elapsedRealtime()+20000;
            do {
                requested.clear();inputs.clear();
                for(AccessibilityNodeInfo n:nodes()){for(CharSequence value:new CharSequence[]{n.getText(),n.getHintText(),n.getContentDescription()}){Matcher match=GROUP.matcher(text(value));if(match.find()){int index=Integer.parseInt(match.group(1));if(!requested.contains(index))requested.add(index);}}if(text(n.getClassName()).equals("android.widget.EditText") && n.isVisibleToUser())inputs.add(n);}
                if(requested.size()==2 && inputs.size()==2)break;
                SystemClock.sleep(100);
            }while(SystemClock.elapsedRealtime()<verificationDeadline);
            assertEquals(2,requested.size());assertEquals(2,inputs.size());
            for(int i=0;i<2;i++){Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,groups.get(requested.get(i)));assertTrue(inputs.get(i).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args));}
            clickText("确认并保存");await("confirmed recovery",n->text(n.getText()).contains("恢复密钥已确认"));clickText("导出加密备份");
            await("system picker",n->text(n.getPackageName()).contains("documentsui"));JSONObject pending=handoff(context);assertNotNull(pending);requestId=pending.getString("id");
            AccessibilityNodeInfo field=await("filename",n->text(n.getClassName()).equals("android.widget.EditText"));Bundle filename=new Bundle();filename.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Yunji-Release-"+UUID.randomUUID()+".yunji");assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,filename));
            click(await("save",n->n.isEnabled() && (text(n.getViewIdResourceName()).endsWith(":id/action_menu_select") || Arrays.asList("Save","SAVE","保存").contains(text(n.getText())))));
            await("confirm backup",n->text(n.getText()).equals("开始制作加密备份？"));pending=handoff(context);assertNotNull(pending);uri=Uri.parse(pending.getJSONArray("uris").getString(0));clickText("继续");
            await("backup success",n->text(n.getText()).contains("加密备份已保存，并已通过原件及恢复数据校验"));assertNull(handoff(context));
            try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(archive)){assertNotNull(in);byte[] buffer=new byte[65536];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);}
            independentRecoveryCheck(archive,groups);
        } finally {
            final Activity closing=activity;try{if(closing!=null)instrumentation.runOnMainSync(closing::finish);}finally{
                try{if(uri!=null)assertTrue(DocumentsContract.deleteDocument(context.getContentResolver(),uri));}finally{
                    try{JSONObject pending=handoff(context);if(pending!=null && (requestId==null || requestId.equals(pending.getString("id"))))new AtomicFile(new File(context.getNoBackupFilesDir(),"file-handoff.enc")).delete();}finally{
                        try{if(previous==null)settings.delete();else{FileOutputStream out=settings.startWrite();try{out.write(previous);settings.finishWrite(out);}catch(Throwable failure){settings.failWrite(out);throw failure;}}}finally{groups.clear();archive.delete();}
                    }
                }
            }
        }
    }
}
