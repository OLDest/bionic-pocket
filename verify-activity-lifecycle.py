"""Run production Activity lifecycle methods with queued callbacks and overlapping owners."""
import os,pathlib,subprocess,xml.etree.ElementTree as ET
root=pathlib.Path(__file__).resolve().parent
source=(root/'android/java/ru/oldest/bionicpocket/MainActivity.java').read_text(encoding='utf-8')
def method(text,signature):
    start=text.index(signature);brace=text.index('{',start);depth=0;quote=None;escape=False
    for i in range(brace,len(text)):
        ch=text[i]
        if quote:
            if escape:escape=False
            elif ch=='\\':escape=True
            elif ch==quote:quote=None
            continue
        if ch in ('"',"'"):quote=ch;continue
        if ch=='{':depth+=1
        elif ch=='}':
            depth-=1
            if depth==0:return text[start:i+1]
    raise AssertionError('Unbalanced production method')
methods='\n'.join(method(source,s) for s in ['public void onPause(','public void onDestroy(','public void onConfigurationChanged(','private void postAlive(','private void setNetworkBusy(']).replace('android.content.res.Configuration','Configuration')
notifications=(root/'android/java/ru/oldest/bionicpocket/MessageNotifications.java').read_text(encoding='utf-8')
busy=method(notifications,'static synchronized void setBusy(')
manifest=ET.parse(root/'android/AndroidManifest.xml').getroot()
activity=manifest.find('application/activity');assert {'orientation','screenSize'}<=set(activity.attrib['{http://schemas.android.com/apk/res/android}configChanges'].split('|'))
code=r'''
import java.util.*;import java.util.concurrent.*;
class Configuration {}
class BaseActivity {
  boolean changing,finishing,platformDestroyed;int configCalls,pauseCalls,destroyCalls;
  boolean isChangingConfigurations(){return changing;}boolean isFinishing(){return finishing;}boolean isDestroyed(){return platformDestroyed;}
  public void onPause(){pauseCalls++;}public void onDestroy(){platformDestroyed=true;destroyCalls++;}public void onConfigurationChanged(Configuration c){configCalls++;}
}
public class LifecycleProbe extends BaseActivity {
  static class Handler {List<Runnable> queued=new ArrayList<>();void post(Runnable r){queued.add(r);}void removeCallbacks(Runnable r){queued.removeIf(x->x==r);}void removeCallbacksAndMessages(Object token){queued.clear();}void drain(){List<Runnable> copy=new ArrayList<>(queued);queued.clear();for(Runnable r:copy)r.run();}}
  static class Root {int layouts,insets;void requestLayout(){layouts++;}void requestApplyInsets(){insets++;}}
  static class MessageNotifications {
    static volatile boolean visible,busy;
    static final Set<Object> networkOwners=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
    BUSY_METHOD
  }
  boolean foreground=true;volatile boolean destroyed;final Handler main=new Handler();final Runnable tick=()->{};Root root=new Root();
  final ExecutorService io=Executors.newSingleThreadExecutor(),updateIo=Executors.newSingleThreadExecutor(),fileIo=Executors.newSingleThreadExecutor();final Map<String,ExecutorService> assistantIo=new HashMap<>();
  int saves,syncStarts;String draft="draft survives rotation";final Object originalChat=new Object();Object current=originalChat;
  void save(){saves++;}void startMessageSync(){syncStarts++;}
  PRODUCTION_METHODS
  static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
  public static void main(String[] args){
    LifecycleProbe live=new LifecycleProbe();ExecutorService lane=Executors.newSingleThreadExecutor();live.assistantIo.put("gonets",lane);
    int[] delivered={0};live.postAlive(()->delivered[0]++);
    for(int i=0;i<100;i++)live.onConfigurationChanged(new Configuration());
    check(live.current==live.originalChat&&live.draft.equals("draft survives rotation"),"rotation discarded conversation/draft");
    check(!lane.isShutdown()&&live.assistantIo.size()==1&&live.saves==0&&live.syncStarts==0,"rotation restarted network or background work");
    check(live.root.layouts==100&&live.root.insets==100&&live.configCalls==100,"rotation did not resize layout/insets");live.main.drain();check(delivered[0]==1,"rotation lost active response");
    live.changing=true;live.onPause();check(live.saves==1&&live.syncStarts==0,"configuration pause started background service");
    live.changing=false;live.onPause();check(live.syncStarts==1,"normal background transition lost service fallback");
    // A queued dialog/response from the old window must never run after destruction.
    live.postAlive(()->{throw new AssertionError("old activity window callback ran");});live.onDestroy();live.main.drain();
    live.postAlive(()->{throw new AssertionError("worker posted after destroy");});check(live.main.queued.isEmpty(),"destroyed owner retained new callbacks");
    check(lane.isShutdown()&&live.io.isShutdown()&&live.fileIo.isShutdown()&&live.updateIo.isShutdown(),"retired work lanes leaked");
    LifecycleProbe platform=new LifecycleProbe();platform.postAlive(()->{throw new AssertionError("platform-destroyed window accessed");});platform.platformDestroyed=true;platform.main.drain();platform.onDestroy();
    LifecycleProbe finishing=new LifecycleProbe();finishing.postAlive(()->{throw new AssertionError("finishing window accessed");});finishing.finishing=true;finishing.main.drain();finishing.onDestroy();
    // Destroying an old owner cannot clear a new owner's network exclusion.
    LifecycleProbe old=new LifecycleProbe(),next=new LifecycleProbe();old.setNetworkBusy(true);next.setNetworkBusy(true);old.onDestroy();check(MessageNotifications.busy,"old activity cleared new activity's busy state");next.setNetworkBusy(false);check(!MessageNotifications.busy,"destroyed owner kept background sync blocked");next.onDestroy();
    System.out.println("PASS: 100 in-place rotations; retained request/draft; late dialog/response guards; config-pause background exclusion; owner busy cleanup; work-lane shutdown");
  }
}
'''.replace('BUSY_METHOD',busy).replace('PRODUCTION_METHODS',methods)
out=root/'build/lifecycle-verification';out.mkdir(parents=True,exist_ok=True);(out/'LifecycleProbe.java').write_text(code,encoding='utf-8')
tools=pathlib.Path('C:/Users/OLDest/Documents/Codex/2026-11-15/go-https-pikabu-ru-story-yandeks/yago/.tools');jdk=pathlib.Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else next((tools/'jdk').glob('jdk-*'))
for args in [[jdk/'bin/javac.exe','-encoding','UTF-8','-d',out,out/'LifecycleProbe.java'],[jdk/'bin/java.exe','-cp',out,'LifecycleProbe']]:subprocess.run(['rtk','proxy',*[str(x) for x in args]],check=True,cwd=root)
