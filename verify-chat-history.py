"""Exercise local deletion against production foreground/background reply merge methods."""
import pathlib,runpy,subprocess
root=pathlib.Path(__file__).resolve().parent
base=runpy.run_path(str(root/'verify-pending-notifications.py'))
out=root/'build/delivery-verification'
probe=(out/'NotificationProbe.java').read_text(encoding='utf-8').replace('NotificationProbe','HistoryProbe')
start=probe.index('  public static void main(String[] args){');end=probe.index('    private void submit(',start)
test=r'''  public static void main(String[] args){
    JSONObject user=new JSONObject().put("role","user").put("content","first"),answer=new JSONObject().put("role","assistant").put("jobId","old-job").put("handoffId","old-handoff").put("state","pending");
    JSONObject keep=new JSONObject().put("role","assistant").put("jobId","keep-job").put("state","completed").put("content","keep");
    JSONArray messages=new JSONArray().put(user).put(answer).put(keep);JSONObject chat=new JSONObject().put("id","chat").put("messages",messages).put("assistant","domovoy").put("model","coder").put("endpoint","http://office").put("draftText","unsent");
    check(ChatHistory.delete(chat,new HashSet<>(Collections.singleton(user)))==1&&messages.length()==2&&messages.optJSONObject(0)==answer,"deleting user message removed its unselected answer");
    check(ChatHistory.delete(chat,new HashSet<>(Collections.singleton(answer)))==1&&messages.length()==1&&messages.optJSONObject(0)==keep,"selected deletion damaged other messages/order");
    JSONObject late=new JSONObject().put("handoffId","old-handoff").put("requestId","old-job").put("version","late-v2").put("status","completed").put("message","late result");
    check(!applyChatReplies(chat,new JSONArray().put(late))&&messages.length()==1,"foreground feed resurrected deleted answer");
    // Recreate chat and marker objects from stored values to model a restart.
    JSONObject restored=new JSONObject(chat.toString());restored.put("hiddenMessages",new JSONObject(chat.optJSONObject("hiddenMessages").toString()));check(ChatHistory.suppressed(restored,late),"restart lost deletion marker");
    JSONObject other=new JSONObject().put("id","other").put("messages",new JSONArray());check(applyChatReplies(other,new JSONArray().put(late)),"deletion affected another chat");
    JSONArray chats=new JSONArray().put(restored);SharedPreferences prefs=new SharedPreferences(chats.toString());Context c=new Context();
    update(c,prefs,"chat","old-job",new JSONObject().put("state","completed").put("answer","stale"),new JSONArray().put(late));check(messages.length()==1&&MessageNotifications.count==0,"background merge resurrected/notified deleted reply");
    JSONObject duplicate=new JSONObject().put("role","assistant").put("codexReplyId","keep-job").put("state","completed");messages.put(duplicate);check(ChatHistory.delete(chat,new HashSet<>(Collections.singleton(keep)))==2&&messages.length()==0,"linked legacy duplicate survived deletion");
    JSONObject pending=new JSONObject().put("role","assistant").put("jobId","pending").put("state","pending");messages.put(pending);check(hasPending(chats),"pending fixture invalid");
    long cleared=java.time.Instant.parse("2026-11-25T18:00:00Z").toEpochMilli();check(ChatHistory.clear(chat,cleared)==1&&messages.length()==0&&!hasPending(chats),"cleared history kept background polling");
    JSONObject later=new JSONObject().put("handoffId","pending").put("version","new").put("status","completed").put("createdAt","2026-11-25T19:00:00Z");check(ChatHistory.suppressed(chat,later),"late result escaped cleared pending marker");
    JSONObject oldUnknown=new JSONObject().put("handoffId","unseen-old").put("version","old").put("status","completed").put("createdAt","2026-11-25T17:00:00Z");check(ChatHistory.suppressed(chat,oldUnknown),"old feed history reappeared after clear");
    JSONObject fresh=new JSONObject().put("handoffId","new-request").put("version","v1").put("status","completed").put("message","new answer").put("createdAt","2026-11-25T18:00:01Z");check(applyChatReplies(chat,new JSONArray().put(fresh))&&messages.length()==1,"clear hid a new request");
    check("domovoy".equals(chat.optString("assistant"))&&"coder".equals(chat.optString("model"))&&"http://office".equals(chat.optString("endpoint"))&&"unsent".equals(chat.optString("draftText")),"clear changed route/settings/draft");
    System.out.println("PASS: selective delete/order; linked duplicates; restart markers; foreground/background replay exclusion; clear stops polling; old feed hidden/new replies accepted; per-chat isolation and settings preserved");
  }
'''
probe=probe[:start]+test+probe[end:];(out/'HistoryProbe.java').write_text(probe,encoding='utf-8')
tools=base['base']
for args in [[tools['javac_path'],'-encoding','UTF-8','-cp',out,'-d',out,out/'HistoryProbe.java'],[tools['java_path'],'-cp',out,'HistoryProbe']]:subprocess.run([tools['rtk'],'proxy',*[str(x) for x in args]],check=True,cwd=root)
