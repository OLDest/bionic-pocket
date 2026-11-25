"""Exercise production background selection/scheduling without Android or network access."""
import pathlib,runpy,subprocess
root=pathlib.Path(__file__).resolve().parent
base=runpy.run_path(str(root/'verify-pending-notifications.py'))
extract=base['base']['declaration']
extract.__globals__['source']=(root/'android/java/ru/oldest/bionicpocket/MessageSyncService.java').read_text(encoding='utf-8')
methods=extract('    static void check(')+'\n'+extract('    static void schedule(')
probe=(root/'build/delivery-verification/NotificationProbe.java').read_text(encoding='utf-8').replace('NotificationProbe','EnergyProbe')
registry_start=probe.index('static class AssistantRegistry {')
registry_end=probe.index('  static class MessageNotifications',registry_start)
probe=probe[:registry_start]+probe[registry_end:]
probe=probe.replace('static boolean busy;static int count;', 'static boolean busy,visible;static void initialize(Context c,JSONArray chats){}static int count;')
probe=probe.replace('static class Context {}', '''static final int MODE_PRIVATE=0;
  interface Cancelled {boolean get();}
  static class JSONException extends RuntimeException {}
  static class Context {SharedPreferences prefs;JobScheduler scheduler=new JobScheduler();Context(SharedPreferences p){prefs=p;}SharedPreferences getSharedPreferences(String n,int m){return prefs;}JobScheduler getSystemService(Class<JobScheduler> c){return scheduler;}}
  static class ComponentName {ComponentName(Context c,Class<?> type){}}
  static class MessageSyncJob {}
  static class JobInfo {static final int NETWORK_TYPE_ANY=1;static class Builder {Builder(int id,ComponentName name){}Builder setRequiredNetworkType(int type){return this;}Builder setPeriodic(long ms){check(ms==900000L,"wrong background interval");return this;}JobInfo build(){return new JobInfo();}}}
  static class JobScheduler {JobInfo job;int schedules,cancels;JobInfo getPendingJob(int id){return job;}void cancel(int id){job=null;cancels++;}void schedule(JobInfo j){job=j;schedules++;}}
  static class AssistantRegistry {static String origin(SharedPreferences p,String id){return "https://helper";}static class Connection {}static Connection resolve(SharedPreferences p,String a,String b,String c){return new Connection();}}
  static boolean hasPending(Context c){return hasPending(new JSONArray(c.prefs.value));}
  static final List<String> requests=new ArrayList<>();
  static JSONObject get(AssistantRegistry.Connection c,String route){requests.add(route);return new JSONObject().put("state","running").put("replies",new JSONArray());}
''')
probe=probe.replace('String value;SharedPreferences', 'Map<String,Integer> ints=new HashMap<>();int getInt(String k,int d){return ints.getOrDefault(k,d);}SharedPreferences putInt(String k,int n){ints.put(k,n);return this;}void apply(){}String value;SharedPreferences')
start=probe.index('  public static void main(String[] args){')
end=probe.index('    private void submit(',start)
probe=probe[:start]+'''  public static void main(String[] args){
    check(!SyncPolicy.burstExpired(1000,120999)&&SyncPolicy.burstExpired(1000,121000),"burst deadline not bounded");
    JSONArray chats=new JSONArray();for(int i=0;i<100;i++)chats.put(new JSONObject().put("id","old"+i).put("messages",new JSONArray().put(new JSONObject().put("role","assistant").put("state","completed"))));
    Context c=new Context(new SharedPreferences(chats.toString()));Handler h=new Handler();
    check(c,h,()->false);check(requests.isEmpty(),"idle history made network requests");schedule(c);check(c.scheduler.job==null,"idle periodic job kept alive");
    for(int i=0;i<4;i++){JSONArray messages=new JSONArray();for(int j=0;j<20;j++)messages.put(new JSONObject().put("role","assistant").put("state","pending").put("jobId","j"+i+"-"+j));chats.put(new JSONObject().put("id","active"+i).put("messages",messages));}
    c.prefs.value=chats.toString();schedule(c);schedule(c);check(c.scheduler.schedules==1,"resume reset scheduled job");
    check(c,h,()->false);check(requests.size()==4,"background pass request budget exceeded");
    check(c,h,()->false);check(requests.size()==8,"second pass request budget exceeded");
    for(int i=0;i<4;i++)check(requests.contains("/mobile/control/replies?chatId=active"+i),"history starved active chat"+i);
    check(c,h,()->false);check(requests.contains("/mobile/jobs/j0-1"),"multiple pending jobs starved");
    int count=requests.size();check(c,h,()->true);check(requests.size()==count,"cancelled worker made requests");MessageNotifications.visible=true;check(c,h,()->false);MessageNotifications.visible=false;check(requests.size()==count,"foreground duplicated background requests");
    JSONObject cancelled=new JSONObject().put("role","assistant").put("state","pending").put("handoffId","old").put("cancelledByOwner",true);check(!SyncPolicy.pending(new JSONArray().put(cancelled)),"cancelled record keeps radio polling");
    c.prefs.value=new JSONArray().toString();schedule(c);check(c.scheduler.job==null,"last completion did not cancel job");
    System.out.println("PASS: zero idle requests; two-minute burst; four-request budget; chat/job fairness; idempotent scheduler; cancellation and foreground exclusion");
  }
'''+probe[end:]
probe=probe.rsplit('}',1)[0]+methods+'\n}\n'
out=root/'build/delivery-verification';(out/'EnergyProbe.java').write_text(probe,encoding='utf-8')
tools=base['base']
for args in [[tools['javac_path'],'-encoding','UTF-8','-cp',out,'-d',out,out/'EnergyProbe.java'],[tools['java_path'],'-cp',out,'EnergyProbe']]:
    subprocess.run([tools['rtk'],'proxy',*[str(a) for a in args]],cwd=root,check=True)
