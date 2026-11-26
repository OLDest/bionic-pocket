"""Run the production inbox merger and history guards on JVM JSON doubles, without Android UI."""
import ast,os,pathlib,subprocess

root=pathlib.Path(__file__).resolve().parent
fixture_source=(root/'verify-delivery-flow.py').read_text(encoding='utf-8')
tree=ast.parse(fixture_source)
namespace={'source':(root/'android/java/ru/oldest/bionicpocket/MainActivity.java').read_text(encoding='utf-8')}
for node in tree.body:
    if isinstance(node,ast.FunctionDef) and node.name=='declaration':
        exec(compile(ast.Module(body=[node],type_ignores=[]),str(root/'verify-delivery-flow.py'),'exec'),namespace)
    if isinstance(node,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='json_double' for t in node.targets):
        doubles=ast.literal_eval(node.value)
extract=namespace['declaration']
methods='\n'.join(extract(marker) for marker in ['    private static boolean applyCodexReply(', '    static boolean applyCodexReplies(', '    static boolean applyChatReplies('])
history=(root/'android/java/ru/oldest/bionicpocket/ChatHistory.java').read_text(encoding='utf-8')
history=history[history.index('final class ChatHistory'):].replace('final class ChatHistory','static final class ChatHistory',1)
namespace['source']=(root/'android/java/ru/oldest/bionicpocket/PocketInbox.java').read_text(encoding='utf-8')
merge=extract('    static JSONArray merge(').replace('MainActivity.','')
program='import java.util.*;\npublic class PocketInboxProbe {\n'+doubles[doubles.index('static class JSONObject'):]+'''
static class JSONException extends RuntimeException {}
static void put(JSONObject object,String key,Object value){object.put(key,value);}
static long serverTime(String time){try{return java.time.Instant.parse(time).toEpochMilli();}catch(Exception ignored){return 0;}}
'''+methods+'\n'+history+'\n'+merge+r'''
static JSONObject reply(String id,String source,String version){return new JSONObject().put("handoffId",id).put("requestId",id).put("chatId",source).put("version",version).put("status","completed").put("message","result "+version).put("updatedAt","2026-11-26T10:00:00Z");}
public static void main(String[] args){
    JSONArray chats=new JSONArray();JSONObject receipts=new JSONObject();JSONObject first=reply("task","missing","v1");
    JSONArray ack=merge(chats,receipts,"domovoy","connection","http://office",new JSONArray().put(first));
    check(chats.length()==1&&ack.length()==1,"missing target did not create one chat and receipt");
    JSONObject created=chats.optJSONObject(0);String local=created.optString("id");JSONArray messages=created.optJSONArray("messages");
    check(!local.equals("missing")&&"missing".equals(created.optString("inboxSourceChatId")),"original identity was reused instead of a new conversation");
    check("domovoy".equals(created.optString("assistant"))&&"connection".equals(created.optString("connectionId")),"new chat lost its source connection");
    check(messages.length()==1&&messages.optJSONObject(0).optString("content").contains("result v1"),"reply content was lost");
    merge(chats,receipts,"domovoy","connection","http://office",new JSONArray().put(first));check(chats.length()==1&&messages.length()==1,"retry duplicated chat or message");
    JSONObject second=reply("other-task","missing","v2");merge(chats,receipts,"domovoy","connection","http://office",new JSONArray().put(second));check(chats.length()==1&&messages.length()==2,"same source created another recovery chat");
    // Receipt serialization survives process-local reconstruction, including out-of-order replay.
    receipts=new JSONObject(receipts.toString());chats.remove(0);
    merge(chats,receipts,"domovoy","connection","http://office",new JSONArray().put(first));check(chats.length()==0,"persisted receipt recreated a locally deleted chat");
    JSONObject archived=new JSONObject().put("id","archived").put("assistant","domovoy").put("endpoint","http://office").put("archived",true).put("messages",new JSONArray());chats.put(archived);
    ack=merge(chats,receipts,"domovoy","connection","http://office",new JSONArray().put(reply("archive-task","archived","v1")));
    check(ack.length()==1&&chats.length()==1&&archived.optBoolean("archived")&&archived.optJSONArray("messages").length()==0,"archived chat was restored, changed or bypassed with another chat");
    JSONObject active=new JSONObject().put("id","active").put("assistant","domovoy").put("endpoint","http://office").put("messages",new JSONArray());chats.put(active);
    merge(chats,receipts,"domovoy","connection","http://office",new JSONArray().put(reply("active-task","active","v1")));check(chats.length()==2&&active.optJSONArray("messages").length()==1,"active target was not reused");
    ChatHistory.delete(active,new HashSet<>(Collections.singleton(active.optJSONArray("messages").optJSONObject(0))));
    merge(chats,receipts,"domovoy","connection","http://office",new JSONArray().put(reply("active-task","active","v2")));check(active.optJSONArray("messages").length()==0,"deleted message was resurrected by a later version");
    merge(chats,receipts,"gonets","remote","https://remote",new JSONArray().put(reply("remote-task","active","v1")));check(chats.length()==3&&active.optJSONArray("messages").length()==0,"delivery crossed assistant connections");
    check(merge(chats,receipts,"domovoy","connection","http://office",new JSONArray().put(new JSONObject())).length()==0,"invalid reply was acknowledged");
    System.out.println("PASS: missing/active/archive routing; source reuse; duplicate/out-of-order replay; persisted receipts; deletion markers; assistant isolation. JVM doubles, no device/UI.");
}
}
'''
out=root/'build/inbox-verification';out.mkdir(parents=True,exist_ok=True)
source=out/'PocketInboxProbe.java';source.write_text(program,encoding='utf-8')
jdk=pathlib.Path(os.environ['JAVA_HOME'])
rtk=os.environ.get('RTK',str(pathlib.Path.home()/'.local/bin/rtk.exe'))
for args in [[jdk/'bin/javac.exe','-encoding','UTF-8','-d',out,source],[jdk/'bin/java.exe','-cp',out,'PocketInboxProbe']]:
    subprocess.run([rtk,'proxy',*[str(value) for value in args]],check=True,cwd=root)
