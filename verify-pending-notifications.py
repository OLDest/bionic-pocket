"""Execute production background merge and notification deduplication on JVM doubles."""
import pathlib,runpy,subprocess
root=pathlib.Path(__file__).resolve().parent
base=runpy.run_path(str(root/'verify-delivery-flow.py'))
cards=base['declaration']('    static boolean applyCodexReplies(')
source=(root/'android/java/ru/oldest/bionicpocket/MessageSyncService.java').read_text(encoding='utf-8')
# Reuse the balanced production-method extractor against this service.
base['declaration'].__globals__['source']=source
method=base['declaration']('    private static void update(').replace('MainActivity.','')
policy=base['declaration']('    static boolean hasPending(JSONArray chats)')
probe=(root/'build/delivery-verification/MainFlowProbe.java').read_text(encoding='utf-8')
probe=probe.replace('public class MainFlowProbe','public class NotificationProbe').replace('MainFlowProbe','NotificationProbe')
probe=probe.replace('static class MessageNotifications {static boolean busy;}', '''  static class MessageNotifications {static boolean busy;static int count;static void received(Context c,String chat,String title,String key,String body){count++;check(chat.equals("chat"),"wrong notification chat");}}
  static class Context {}
  static class SharedPreferences {
    String value;SharedPreferences(String v){value=v;}String getString(String k,String fallback){return value;}
    SharedPreferences edit(){return this;}SharedPreferences putString(String k,String v){value=v;return this;}boolean commit(){return true;}
  }''')
probe=probe.replace('  final List<JSONObject> data=new ArrayList<>();','''  static final Map<String,List<JSONObject>> stored=new HashMap<>();
  final List<JSONObject> data=new ArrayList<>();
  JSONArray(){} JSONArray(String value){if(stored.containsKey(value))data.addAll(stored.get(value));}
  public String toString(){String id=UUID.randomUUID().toString();stored.put(id,new ArrayList<>(data));return id;}''')
start=probe.index('  public static void main(String[] args){')
end=probe.index('    private void submit(',start)
probe=probe[:start]+'''  public static void main(String[] args){
    JSONArray messages=new JSONArray().put(new JSONObject().put("role","assistant").put("state","pending").put("jobId","job"));
    JSONArray chats=new JSONArray().put(new JSONObject().put("id","chat").put("title","Test").put("messages",messages));
    SharedPreferences prefs=new SharedPreferences(chats.toString());Context c=new Context();
    JSONObject job=new JSONObject().put("state","completed").put("answer","reply").put("completedAt","2026-11-20T10:00:00Z");
    update(c,prefs,"chat","job",job,null);check(MessageNotifications.count==1,"missing background notification");
    update(c,prefs,"chat","job",job,null);check(MessageNotifications.count==1,"duplicate completion notification");
    check(messages.optJSONObject(0).optLong("receivedAt",0)>0,"background receive timestamp missing");
    JSONArray replies=new JSONArray().put(new JSONObject().put("handoffId","handoff").put("version","v1").put("status","received").put("message","Codex receipt").put("updatedAt","2026-11-20T11:00:00Z"));
    update(c,prefs,"chat",null,null,replies);check(MessageNotifications.count==2,"missing Codex notification");
    update(c,prefs,"chat",null,null,replies);check(MessageNotifications.count==2,"duplicate Codex notification");
    replies.optJSONObject(0).put("version","v2").put("message","Result");update(c,prefs,"chat",null,null,replies);check(MessageNotifications.count==3,"updated result not notified");
    update(c,prefs,"deleted-chat",null,null,replies);check(MessageNotifications.count==3,"deleted conversation notified");
    check(!hasPending(new JSONArray()),"empty queue keeps foreground service");
    JSONObject request=new JSONObject().put("role","assistant").put("state","pending").put("jobId","pending");
    JSONArray pendingMessages=new JSONArray().put(request);
    JSONArray pendingChats=new JSONArray().put(new JSONObject().put("messages",pendingMessages));
    check(hasPending(pendingChats),"pending request not watched");
    request.put("state","completed");check(!hasPending(pendingChats),"completed answer keeps service");
    request.put("handoffId","codex-pending");check(hasPending(pendingChats),"awaited Codex result lost");
    JSONObject result=new JSONObject().put("role","assistant").put("codexReplyId","codex-pending").put("codexReplyStatus","received");
    pendingMessages.put(result);check(hasPending(pendingChats),"Codex receipt ends waiting");
    result.put("codexReplyStatus","in_progress");check(hasPending(pendingChats),"Codex work ends waiting");
    result.put("codexReplyStatus","completed");check(!hasPending(pendingChats),"Codex completion keeps notification");
    result.put("codexReplyStatus","blocked");check(!hasPending(pendingChats),"terminal blocker keeps notification");
    JSONObject handoffAnswer=new JSONObject();apply(handoffAnswer,new JSONObject().put("state","completed").put("handoffId","from-api"));
    check("from-api".equals(handoffAnswer.optString("handoffId")),"actual apply dropped handoff identity");
    check("received".equals(messages.optJSONObject(1).optString("codexReplyStatus")),"actual reply merge dropped status");
    System.out.println("PASS: production background merge, timestamps, notification dedup and deleted-chat handling");
  }
'''+probe[end:]
probe=probe.rsplit('}',1)[0]+cards+'\n'+method+'\n'+policy+'\n}\n'
out=root/'build/delivery-verification';(out/'NotificationProbe.java').write_text(probe,encoding='utf-8')
jdk=next(pathlib.Path('C:/Users/OLDest/Documents/Codex/2026-11-15/go-https-pikabu-ru-story-yandeks/yago/.tools/jdk').glob('jdk-*'))
rtk='C:/Users/OLDest/.local/bin/rtk.exe'
for args in [[jdk/'bin/javac.exe','-encoding','UTF-8','-cp',out,'-d',out,out/'NotificationProbe.java'],[jdk/'bin/java.exe','-cp',out,'NotificationProbe']]:subprocess.run([rtk,'proxy',*[str(a) for a in args]],cwd=root,check=True)
