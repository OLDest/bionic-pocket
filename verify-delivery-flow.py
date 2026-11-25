"""Run actual extracted Android delivery methods on JVM with deterministic I/O doubles."""
import os,pathlib,re,shutil,subprocess,sys
ROOT=pathlib.Path(__file__).resolve().parent
worker=pathlib.Path(sys.argv[1]).resolve() if len(sys.argv)>1 else ROOT
source=(worker/'android/java/ru/oldest/bionicpocket/MainActivity.java').read_text(encoding='utf-8-sig')
def declaration(marker):
    start=source.index(marker); pos=source.index('{',start); depth=0; quote=None; comment=None; escape=False; i=pos
    while i<len(source):
        c=source[i];n=source[i+1:i+2]
        if comment=='line':
            if c=='\n':comment=None
        elif comment=='block':
            if c=='*' and n=='/':comment=None;i+=1
        elif quote:
            if escape:escape=False
            elif c=='\\':escape=True
            elif c==quote:quote=None
        elif c=='/' and n=='/':comment='line';i+=1
        elif c=='/' and n=='*':comment='block';i+=1
        elif c in ['"',"'"]:quote=c
        elif c=='{':depth+=1
        elif c=='}':
            depth-=1
            if depth==0:return source[start:i+1]
        i+=1
    raise RuntimeError('Unclosed Java declaration '+marker)
exception_match=re.search(r'(?m)^\s*(?:(?:private|public|protected|static|final)\s+)*class ApiException\b',source)
if not exception_match:raise RuntimeError('Production ApiException is missing')
exception=declaration(exception_match.group(0))
imports='import java.io.*; import java.net.*; import java.util.*; import java.util.concurrent.*; import javax.net.ssl.*; import java.security.cert.Certificate; import java.nio.charset.StandardCharsets; import ru.oldest.bionicpocket.DeliveryPolicy; import ru.oldest.bionicpocket.AssistantEndpoint;\n'
json_double=r'''
// Replace only Android credential storage; actual registry/AES logic has its own JVM suite.
static class AssistantRegistry {
 static class Connection {
  final String profileId,origin;Connection(String id,String value){profileId=id;origin=value;}
  String token(){return "fixture-"+profileId;}
  String validate()throws IOException{String base=AssistantEndpoint.validate(profileId,origin,"domovoy".equals(profileId));if("domovoy".equals(profileId))AssistantEndpoint.requirePrivateAddress(base);return base;}
 }
 static Connection resolve(Object prefs,String id,String origin,String pinned)throws IOException{return new Connection(id,origin);}
}
static class MessageNotifications {static boolean busy;}
static void notifyReceived(){}
    private static long serverTime(String value){try{return java.time.Instant.parse(value).toEpochMilli();}catch(Exception e){return 0;}}
static class JSONObject {
  static final Map<String,Map<String,Object>> copies=new HashMap<>();
  final Map<String,Object> data=new LinkedHashMap<>();
  JSONObject(){}
  // Reproduce metadata copies while keeping network response parsing explicit in the I/O double.
  JSONObject(String body){
    if(copies.containsKey(body)){data.putAll(copies.get(body));return;}
    if(!body.trim().startsWith("{")||!body.trim().endsWith("}"))throw new IllegalArgumentException("not JSON");
    java.util.regex.Matcher m=java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
    while(m.find())data.put(m.group(1),m.group(2));
  }
  JSONObject put(String k,Object v){data.put(k,v);return this;}
  String optString(String k){return optString(k,"");}
  String optString(String k,String fallback){Object v=data.get(k);return v==null?fallback:String.valueOf(v);}
  String getString(String k){if(!data.containsKey(k))throw new IllegalArgumentException(k);return optString(k);}
  long optLong(String k,long fallback){return data.containsKey(k)?getLong(k):fallback;}
  boolean optBoolean(String k){return Boolean.TRUE.equals(data.get(k));}
  Object remove(String k){return data.remove(k);}
  // Support optional server upload metadata in the production method.
  boolean has(String k){return data.containsKey(k);}
  // Preserve exact byte counts when the production uploader reads required metadata.
  long getLong(String k){return ((Number)data.get(k)).longValue();}
  // Model the zero offset returned before the first uploaded chunk.
  long optLong(String k){return data.containsKey(k)?getLong(k):0;}
  JSONObject optJSONObject(String k){return (JSONObject)data.get(k);}
  JSONArray optJSONArray(String k){return (JSONArray)data.get(k);}
  // Retain values across the production uploader's JSON metadata copy operation.
  public String toString(){String text=data.toString();copies.put(text,new LinkedHashMap<>(data));return text;}
}
static class JSONArray {
  final List<JSONObject> data=new ArrayList<>();
  JSONArray put(JSONObject v){data.add(v);return this;}
  int length(){return data.size();}
  JSONObject optJSONObject(int i){return data.get(i);}
  // Match Android JSONArray removal for duplicate-card migration.
  Object remove(int i){return data.remove(i);}
}
static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
'''
flow=r'''
  String token="fixture",pollError="",activeOrigin="";
  final Executor io=Runnable::run;
  Executor executor(String assistant){return io;}
  final Map<String,Integer> activeNetwork=new HashMap<>(),pollCursor=new HashMap<>();
  static class Handler {void post(Runnable r){r.run();}}
  final Handler main=new Handler();
  final JSONArray messages=new JSONArray();
  final JSONArray chats=new JSONArray();
  String assistantId(JSONObject chat){return chat.optString("assistant","gonets");}
  final Deque<Object> responses=new ArrayDeque<>();
  static class Call {String route,origin;JSONObject payload;Call(String r,JSONObject p,String o){route=r;payload=p;origin=o;}}
  final List<Call> calls=new ArrayList<>();
  int saves,renders,uploadCalls;
  // Record upload placement relative to actual message recovery and submission calls.
  void uploadAttachments(JSONObject answer){uploadCalls++;}
  JSONArray entries(){return messages;} void render(){renders++;} void save(){saves++;} void toast(String message){}
  static void put(JSONObject j,String k,Object v){j.put(k,v);}
  JSONObject apiFor(JSONObject answer,String route,JSONObject payload)throws Exception {activeOrigin=answer.optString("endpoint");return api(route,payload);}
  JSONObject api(String route,JSONObject payload)throws Exception {
    calls.add(new Call(route,payload,activeOrigin));Object next=responses.removeFirst();
    if(next instanceof Exception)throw (Exception)next;return (JSONObject)next;
  }
  static JSONObject done(){return new JSONObject().put("state","completed").put("answer","delivered");}
  static MainFlowProbe fixture(){MainFlowProbe f=new MainFlowProbe();JSONObject request=new JSONObject().put("requestId","fixed-id").put("text","hello");f.messages.put(new JSONObject().put("state","pending").put("jobId","fixed-id").put("request",request).put("assistantId","gonets").put("endpoint","https://original.invalid"));f.chats.put(new JSONObject().put("messages",f.messages));return f;}
  JSONObject answer(){return messages.optJSONObject(0);}
  static void expectState(MainFlowProbe f,String expected){check(expected.equals(f.answer().optString("state")),"Expected "+expected+", got "+f.answer().optString("state"));check(f.activeNetwork.values().stream().allMatch(n->n==0),"Assistant network counters must reset");}
  public static void main(String[] args){
    for(int status:new int[]{0,408,429,500,503,599})check(DeliveryPolicy.isRetryable(status),"retry "+status);
    for(int status:new int[]{200,400,401,403,404,409})check(!DeliveryPolicy.isRetryable(status),"permanent "+status);
    check(DeliveryPolicy.isGetNotFound("GET",404),"GET404 decision");check(!DeliveryPolicy.isGetNotFound("POST",404),"POST404 must not recover as GET");
    MainFlowProbe f=fixture();f.responses.add(new IOException("offline"));f.submit(f.answer());expectState(f,"pending");
    check("fixed-id".equals(f.answer().optString("jobId")),"offline changes ID");
    f.responses.add(done());f.poll();expectState(f,"completed");check("delivered".equals(f.answer().optString("content")),"reply lost");
    check(f.calls.size()==2&&f.calls.get(1).payload==null,"lost acknowledgement must GET before repost");
    check(f.uploadCalls==1,"existing server job must be recovered before another upload");
    f=fixture();JSONObject original=f.answer().optJSONObject("request");f.responses.add(new ApiException("missing",404));f.responses.add(done());f.poll();expectState(f,"completed");
    check(f.calls.size()==2,"GET404 did not repost");check(f.calls.get(0).route.equals("/mobile/jobs/fixed-id"),"wrong GET route");
    check(f.calls.get(1).route.equals("/mobile/messages")&&f.calls.get(1).payload==original,"repost must use exact saved request");
    check("fixed-id".equals(original.optString("requestId")),"automatic recovery generated another ID");
    check(f.uploadCalls==1,"GET404 must restore attachments before reposting the original request");
    for(int status:new int[]{408,429,500,503}){f=fixture();f.responses.add(new ApiException("temporary",status));f.submit(f.answer());expectState(f,"pending");}
    for(int status:new int[]{400,401,403,404,409}){f=fixture();f.responses.add(new ApiException("permanent",status));f.submit(f.answer());expectState(f,"error");}
    for(int status:new int[]{400,401,403,409}){f=fixture();f.responses.add(new ApiException("permanent",status));f.poll();expectState(f,"error");check(f.calls.size()==1,"permanent GET unexpectedly reposted");}
    f=fixture();f.responses.add(new IOException("offline"));f.poll();expectState(f,"pending");check(!f.answer().optString("pollError").contains("Ответ заберу"),"false guaranteed-answer promise remains");
    f=fixture();f.responses.add(new JSONObject().put("state","failed").put("error","model stopped"));f.poll();expectState(f,"error");
    check(f.answer().optBoolean("retryNew"),"server failure must permit explicit new attempt");
    f.responses.add(new JSONObject().put("state","failed").put("error","Actual old failure"));f.responses.add(done());f.retry(f.answer());expectState(f,"completed");check(!"fixed-id".equals(f.answer().optString("jobId")),"manual failed-job retry must have fresh ID");
    f=fixture();f.apply(f.answer(),new JSONObject().put("state","awaiting_codex").put("answer","Waiting for coordinator"));expectState(f,"pending");check("Waiting for coordinator".equals(f.answer().optString("content")),"coordinator wait message lost");
    f=fixture();f.answer().put("handoffId","cancelled-old").put("codexReplyId","cancelled-old").put("codexReplyStatus","received");f.apply(f.answer(),new JSONObject().put("state","failed").put("cancelledByOwner",true).put("error","Cancelled by owner"));expectState(f,"error");check(!f.answer().optBoolean("retryNew")&&f.answer().optString("handoffId").isEmpty()&&f.answer().optString("codexReplyId").isEmpty(),"cancelled request still polls/retries a handoff");
    f=fixture();f.apply(f.answer(),new JSONObject().put("state","failed").put("cancelledByOwner",true).put("error","Owner cancelled"));f.retry(f.answer());f.submit(f.answer());check(f.calls.isEmpty(),"cancelled record submitted network work");f.apply(f.answer(),new JSONObject().put("state","running"));expectState(f,"error");
    f=fixture();f.answer().put("state","error").put("retryNew",true);f.responses.add(new JSONObject().put("state","failed").put("cancelledByOwner",true).put("error","Owner cancelled"));f.retry(f.answer());expectState(f,"error");check(f.calls.size()==1&&f.calls.get(0).route.startsWith("/mobile/jobs/")&&f.calls.get(0).payload==null&&"fixed-id".equals(f.answer().optString("jobId")),"old cached error bypassed fresh owner cancellation check");
    f=fixture();f.apply(f.answer(),new JSONObject().put("state","running").put("answer","Queued, not yet confirmed"));expectState(f,"pending");check("Queued, not yet confirmed".equals(f.answer().optString("content")),"pending receipt text lost");
    f.apply(f.answer(),new JSONObject().put("state","completed").put("answer","Receipt confirmed"));expectState(f,"completed");check("Receipt confirmed".equals(f.answer().optString("content")),"confirmed receipt lost");
    f=fixture();JSONObject office=new JSONObject().put("state","pending").put("jobId","office-id").put("assistantId","domovoy").put("endpoint","http://192.168.10.20:1236");f.chats.put(new JSONObject().put("messages",new JSONArray().put(office)));
    f.responses.add(new IOException("remote offline"));f.responses.add(done());f.poll();
    check("pending".equals(f.answer().optString("state")),"remote failure lost request");check("completed".equals(office.optString("state")),"one offline assistant blocked the other");
    check(f.calls.get(0).origin.equals("https://original.invalid")&&f.calls.get(1).origin.equals("http://192.168.10.20:1236"),"assistant origin was changed");
    f=fixture();JSONObject later=new JSONObject().put("state","pending").put("jobId","later-id").put("assistantId","gonets").put("endpoint","https://original.invalid");f.chats.put(new JSONObject().put("messages",new JSONArray().put(later)));
    f.responses.add(new JSONObject().put("state","running"));f.poll();f.responses.add(done());f.poll();check("completed".equals(later.optString("state")),"first running job starved another chat");
    System.out.println("PASS: actual submit/poll/retry/apply, offline/404/429/5xx/auth and request-ID recovery");
  }
'''
api=r'''
  String token="fixture",server="https://fixture.invalid";
  final Object prefs=new Object();final JSONObject current=new JSONObject().put("assistant","gonets");
  AssistantRegistry.Connection chatConnection(JSONObject chat)throws IOException{return AssistantRegistry.resolve(prefs,chat.optString("assistant"),server,"");}
  static int status;static String body,lastUrl;static boolean offline;
  static class FakeConnection extends HttpsURLConnection {
    FakeConnection(URL url){super(url);lastUrl=url.toString();} public void connect(){} public void disconnect(){} public boolean usingProxy(){return false;}
    public String getCipherSuite(){return "fixture";}public Certificate[] getLocalCertificates(){return null;}public Certificate[] getServerCertificates(){return null;}
    public int getResponseCode()throws IOException{if(offline)throw new IOException("offline");return status;}
    public InputStream getInputStream(){return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));}
    public InputStream getErrorStream(){return getInputStream();}public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
  }
  public static void main(String[] args)throws Exception {
    URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){protected URLConnection openConnection(URL url){return new FakeConnection(url);}}:null);
    MainApiProbe p=new MainApiProbe();
    for(int code:new int[]{400,401,403,404,409,429,500,503})for(String data:new String[]{"{}","not JSON"}){
      status=code;body=data;try{p.api("/mobile/jobs/fixed-id",null);throw new AssertionError("HTTP error accepted");}catch(ApiException error){check(error.status==code,"HTTP status lost: "+code);}
    }
    status=200;body="{}";check(p.api("/mobile/messages",new JSONObject())!=null,"success rejected");
    p.server="https://changed.invalid";
    p.apiFor(new JSONObject().put("assistantId","gonets").put("endpoint","https://original.invalid"),"/mobile/jobs/fixed-id",null);
    check(lastUrl.equals("https://original.invalid/mobile/jobs/fixed-id"),"pending request followed changed server settings");
    try{p.apiAt("gonets","http://192.168.1.10:1236","/health",null);throw new AssertionError("remote HTTP accepted");}catch(IOException expected){}
    offline=true;try{p.api("/mobile/jobs/fixed-id",null);throw new AssertionError("offline accepted");}catch(IOException expected){}
    System.out.println("PASS: actual api preserves HTTP status for JSON/non-JSON errors and transport failures");
  }
'''
upload=r'''
  static final int ATTACHMENT_CHUNK=65536;
  static class android {static class util {static class Base64 {
    static final int NO_WRAP=2;
    // Use the JDK equivalent of Android's no-wrap base64 encoding in this transport double.
    static String encodeToString(byte[] bytes,int flags){return java.util.Base64.getEncoder().encodeToString(bytes);}
  }}}
  static class Handler {
    // Run UI callbacks immediately so the upload's byte operations remain deterministic.
    void post(Runnable r){r.run();}
  }
  final Handler main=new Handler();final ByteArrayOutputStream stored=new ByteArrayOutputStream();
  File local;JSONObject metadata;boolean loseReply=true,conflict=false;int posts;
  // Resolve the exact original selected by the test, replacing only Android private-storage lookup.
  File attachmentFile(String id){return local;}
  // Ignore rendering while executing the unmodified production upload method.
  void render(){}
  // Preserve production progress metadata without requiring Android widgets.
  static void put(JSONObject j,String k,Object v){j.put(k,v);}
  // Simulate only server I/O, including a lost acknowledgement after bytes were already committed.
  JSONObject apiFor(JSONObject answer,String route,JSONObject payload)throws Exception {
    if(payload==null&&stored.size()==0)throw new ApiException("not uploaded",404);
    if(payload!=null){
      posts++;check(payload.getLong("offset")==stored.size(),"upload resumed at the wrong offset");
      byte[] bytes=java.util.Base64.getDecoder().decode(payload.getString("data"));check(bytes.length<=65536,"oversized upload chunk");
      stored.write(bytes);if(loseReply){loseReply=false;throw new IOException("response lost after commit");}
    }
    return new JSONObject().put("received",(long)stored.size()).put("size",metadata.getLong("size")).put("sha256",conflict?"bad":metadata.getString("sha256")).put("state",stored.size()==metadata.getLong("size")?"completed":"uploading");
  }
  // Exercise the extracted uploader with real original-file bytes and an interruption between chunks.
  public static void main(String[] args)throws Exception {
    MainUploadProbe p=new MainUploadProbe();p.local=File.createTempFile("bionic-original-",".bin");
    try{
      byte[] bytes=new byte[170001];new Random(120).nextBytes(bytes);try(FileOutputStream out=new FileOutputStream(p.local)){out.write(bytes);}
      StringBuilder digest=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))digest.append(String.format(Locale.ROOT,"%02x",b&255));
      p.metadata=new JSONObject().put("id",UUID.randomUUID().toString()).put("name","original.bin").put("mime","application/octet-stream").put("size",(long)bytes.length).put("sha256",digest.toString());
      JSONObject request=new JSONObject().put("attachments",new JSONArray().put(p.metadata)),answer=new JSONObject().put("request",request);
      try{p.uploadAttachments(answer);throw new AssertionError("lost acknowledgement was hidden");}catch(IOException expected){}
      check(p.stored.size()==65536,"first chunk was not committed before interruption");p.uploadAttachments(answer);check(Arrays.equals(bytes,p.stored.toByteArray()),"resumed upload changed the original bytes");
      int posts=p.posts;check(p.local.delete(),"could not remove original test file");p.uploadAttachments(answer);check(p.posts==posts,"completed upload required another local read or POST");
      p.conflict=true;try{p.uploadAttachments(answer);throw new AssertionError("wrong remote digest accepted");}catch(IOException expected){}
      System.out.println("PASS: actual attachment uploader, chunk bounds, lost acknowledgements, resume and checksum conflicts");
    }finally{if(p.local.exists())p.local.delete();}
  }
'''
replies=r'''
  int replyChatIndex;
  String token="fixture",lastOrigin="",lastRoute="";int calls,saves;
  final Map<String,Integer> activeNetwork=new HashMap<>();
  final JSONObject current=new JSONObject().put("id","reply-chat").put("assistant","domovoy").put("messages",new JSONArray());
  final JSONArray chats=new JSONArray().put(current);
  JSONObject response;
  static class Handler {
    // Keep callback order deterministic while exercising the actual reply polling code.
    void post(Runnable r){r.run();}
  }
  final Handler main=new Handler();
  // Substitute only the UI executor, keeping the production assistant choice intact.
  Executor executor(String assistant){return Runnable::run;}
  // Resolve the fixture's chat identity as the application does.
  String assistantId(JSONObject chat){return chat.optString("assistant");}
  // Give each assistant a distinct fixture origin to detect cross-profile routing.
  String endpoint(String assistant){return assistant.equals("domovoy")?"http://192.168.88.1:1236":"https://gonets.invalid";}
  String chatEndpoint(JSONObject chat){return endpoint(assistantId(chat));}
  AssistantRegistry.Connection chatConnection(JSONObject chat){return new AssistantRegistry.Connection(assistantId(chat),chatEndpoint(chat));}
  JSONObject apiConnection(AssistantRegistry.Connection connection,String route,JSONObject payload){calls++;lastOrigin=connection.origin;lastRoute=route;return response;}
  // Record the production poll's destination while replacing the network response.
  JSONObject apiAt(String assistant,String origin,String route,JSONObject payload){calls++;lastOrigin=origin;lastRoute=route;return response;}
  // Preserve actual card changes in the JSON double.
  static void put(JSONObject j,String k,Object v){j.put(k,v);}
  // Count persistent updates without requiring Android preferences.
  void save(){saves++;}
  // Ignore widgets in this delivery-policy probe.
  void render(){}
  // Construct a distinct server reply version for the original handoff.
  static JSONObject update(String version,String status,String text){return new JSONObject().put("handoffId","handoff-one").put("version",version).put("status",status).put("message",text);}
  // Verify reply routing, deduplication and replacement using the actual merged production methods.
  public static void main(String[] args){
    MainReplyProbe p=new MainReplyProbe();p.response=new JSONObject().put("replies",new JSONArray().put(update("one","received","accepted")));p.pollReplies();
    JSONArray messages=p.current.optJSONArray("messages");check(messages.length()==1,"missing reply card");check(p.lastOrigin.startsWith("http://192.168.88.1"),"office reply went to Gonets");check(p.lastRoute.endsWith("chatId=reply-chat"),"wrong original chat");
    p.pollReplies();check(messages.length()==1&&p.saves==1,"same reply duplicated or resaved");p.response=new JSONObject().put("replies",new JSONArray().put(update("two","completed","finished")));p.pollReplies();check(messages.length()==1&&messages.optJSONObject(0).optString("content").contains("finished"),"new reply did not update original card");
    p.current.put("assistant","gonets");p.pollReplies();check(p.lastOrigin.equals("https://gonets.invalid"),"remote reply went to Domovoy");int calls=p.calls;p.activeNetwork.put("gonets",1);p.pollReplies();check(p.calls==calls,"reply poll ignored active network request");
    // Job-first delivery must let reply polling reuse the exact original request card.
    MainReplyProbe first=new MainReplyProbe();JSONArray firstList=first.current.optJSONArray("messages");
    JSONObject original=new JSONObject().put("role","assistant").put("jobId","handoff-one").put("state","pending").put("request",new JSONObject().put("requestId","handoff-one"));firstList.put(original);
    JSONObject completed=update("done","completed","one result").put("updatedAt","2026-11-21T16:00:00Z");
    first.apply(original,new JSONObject().put("state","completed").put("answer","one result").put("codexReply",completed));
    first.response=new JSONObject().put("replies",new JSONArray().put(completed));first.pollReplies();
    check(firstList.length()==1&&firstList.optJSONObject(0)==original,"job-first response duplicated the original card");
    // Reply-first delivery must remain pending until terminal, and ignore stale job snapshots.
    MainReplyProbe reverse=new MainReplyProbe();JSONArray reverseList=reverse.current.optJSONArray("messages");JSONObject reverseOriginal=new JSONObject().put("role","assistant").put("jobId","handoff-one").put("state","pending");reverseList.put(reverseOriginal);
    reverse.response=new JSONObject().put("replies",new JSONArray().put(update("start","received","working")));reverse.pollReplies();check(reverseOriginal.optString("state").equals("pending"),"received reply ended the request early");
    reverse.response=new JSONObject().put("replies",new JSONArray().put(completed));reverse.pollReplies();reverse.apply(reverseOriginal,new JSONObject().put("state","completed").put("codexReply",completed));
    MainReplyProbe cancelledProbe=new MainReplyProbe();JSONObject cancelledMessage=new JSONObject().put("role","assistant").put("jobId","cancelled-id").put("state","pending");cancelledProbe.apply(cancelledMessage,new JSONObject().put("cancelledByOwner",true).put("state","failed").put("error","Owner cancelled"));JSONArray cancelledList=new JSONArray().put(cancelledMessage);applyCodexReplies(cancelledList,new JSONArray().put(update("cancelled-id","received","late read")).put(update("cancelled-id","in_progress","late work")));check("error".equals(cancelledMessage.optString("state"))&&cancelledMessage.optBoolean("cancelledByOwner")&&cancelledMessage.optString("codexReplyId").isEmpty(),"stale Codex reply resurrected owner cancellation");
    reverse.apply(reverseOriginal,new JSONObject().put("state","running").put("answer","stale queued"));reverse.apply(reverseOriginal,new JSONObject().put("state","running").put("codexReply",update("old","in_progress","stale working")));
    check(reverseList.length()==1&&reverseOriginal.optString("content").contains("one result")&&reverseOriginal.optString("state").equals("completed"),"reply-first completion regressed or duplicated");
    reverse.apply(reverseOriginal,new JSONObject().put("state","completed").put("codexReply",update("older","completed","obsolete result").put("updatedAt","2026-11-20T16:00:00Z")));check(reverseOriginal.optString("content").contains("one result"),"older completed snapshot replaced newer result");
    // Heal the already-persisted screenshot case without deleting another request with identical text.
    MainReplyProbe legacy=new MainReplyProbe();JSONArray legacyList=legacy.current.optJSONArray("messages");JSONObject oldJob=new JSONObject().put("role","assistant").put("jobId","handoff-one").put("state","completed").put("content","one result").put("clientQueuedAt","original-time");
    legacyList.put(oldJob).put(new JSONObject().put("role","assistant").put("codexReplyId","handoff-one").put("codexReplyVersion","done").put("content","one result")).put(new JSONObject().put("role","assistant").put("jobId","another-request").put("content","one result"));
    legacy.response=new JSONObject().put("replies",new JSONArray().put(completed));legacy.pollReplies();
    check(legacyList.length()==2&&legacyList.optJSONObject(0)==oldJob&&legacyList.optJSONObject(1).optString("jobId").equals("another-request"),"legacy duplicate repair removed the wrong request");check(oldJob.optString("clientQueuedAt").equals("original-time"),"migration lost request metadata");check(oldJob.optLong("sentAt",0)>0&&oldJob.optLong("receivedAt",0)>0,"coordinator delivery timestamps missing");
    int saved=legacy.saves;legacy.pollReplies();check(legacyList.length()==2&&legacy.saves==saved,"repeat poll duplicated or resaved migrated reply");
    // Old persisted duplicates must heal even after the server's last-20 reply feed no longer contains them.
    MainReplyProbe archived=new MainReplyProbe();JSONArray archivedList=archived.current.optJSONArray("messages");JSONObject archivedOriginal=new JSONObject().put("role","assistant").put("jobId","older-handoff").put("state","completed").put("content","verified old result");
    archivedList.put(archivedOriginal).put(new JSONObject().put("role","assistant").put("codexReplyId","older-handoff").put("codexReplyVersion","old-version").put("content","older duplicate"));
    archived.response=new JSONObject().put("replies",new JSONArray());archived.pollReplies();check(archivedList.length()==1&&archivedList.optJSONObject(0)==archivedOriginal&&archivedOriginal.optString("content").equals("verified old result"),"old duplicate outside recent reply feed was not healed");
    System.out.println("PASS: job/reply arrival orders, pending/completed semantics, stale snapshots and legacy duplicate repair");
    System.out.println("PASS: actual Codex reply cards, version deduplication and assistant-specific polling");
  }
'''
out=worker/'build/delivery-verification';out.mkdir(parents=True,exist_ok=True)
(out/'MainFlowProbe.java').write_text(imports+'public class MainFlowProbe {\n'+json_double+'\n'+exception+'\n'+flow+'\n'+'\n'.join(declaration(('    static void ' if name=='apply' else '    private void ')+name+'(') for name in ['submit','poll','retry','apply','beginNetwork','finishNetwork'])+'\n'+declaration('    private static boolean applyCodexReply(')+'\n'+declaration('    private JSONObject nextPending(')+'\n'+declaration('    private Set<String> pendingAssistantIds(')+'\n}\n',encoding='utf-8')
(out/'MainApiProbe.java').write_text(imports+'public class MainApiProbe {\n'+json_double+'\n'+exception+'\n'+api+'\n'+declaration('    private JSONObject api(')+'\n'+declaration('    private JSONObject apiFor(')+'\n'+declaration('    private JSONObject apiAt(')+'\n'+declaration('    private JSONObject apiConnection(')+'\n'+declaration('    private static String read(')+'\n}\n',encoding='utf-8')
(out/'MainUploadProbe.java').write_text(imports+'public class MainUploadProbe {\n'+json_double+'\n'+exception+'\n'+upload+'\n'+declaration('    private void uploadAttachments(')+'\n}\n',encoding='utf-8')
(out/'MainReplyProbe.java').write_text(imports+'public class MainReplyProbe {\n'+json_double+'\n'+replies+'\n'+declaration('    static void apply(')+'\n'+declaration('    private static boolean applyCodexReply(')+'\n'+declaration('    static boolean applyCodexReplies(')+'\n'+declaration('    private void pollReplies(')+'\n'+declaration('    private void beginNetwork(')+'\n'+declaration('    private void finishNetwork(')+'\n}\n',encoding='utf-8')
java_home=os.environ.get('JAVA_HOME')
if not java_home:
    installed=pathlib.Path('C:/Users/OLDest/Documents/Codex/2026-11-15/go-https-pikabu-ru-story-yandeks/yago/.tools/jdk')
    candidates=list(installed.glob('jdk-*')) if installed.is_dir() else []
    if candidates:java_home=str(candidates[0])
javac_path=str(pathlib.Path(java_home)/'bin/javac.exe') if java_home else shutil.which('javac')
java_path=str(pathlib.Path(java_home)/'bin/java.exe') if java_home else shutil.which('java')
if not javac_path or not java_path or not pathlib.Path(javac_path).is_file() or not pathlib.Path(java_path).is_file():raise RuntimeError('Set JAVA_HOME or put java and javac on PATH')
rtk=os.environ.get('RTK',str(pathlib.Path.home()/'.local/bin/rtk.exe'))
def run(*args):
    """Run a real JVM verification command through the current installation's RTK."""
    subprocess.run([rtk,'proxy',*[str(a) for a in args]],check=True,cwd=worker)
run(javac_path,'-encoding','UTF-8','-d',out,out/'MainFlowProbe.java',out/'MainApiProbe.java',out/'MainUploadProbe.java',out/'MainReplyProbe.java',worker/'android/java/ru/oldest/bionicpocket/DeliveryPolicy.java',worker/'android/java/ru/oldest/bionicpocket/AssistantEndpoint.java',worker/'android/test/AssistantEndpointTest.java')
run(java_path,'-cp',out,'MainApiProbe')
run(java_path,'-cp',out,'MainFlowProbe')
run(java_path,'-cp',out,'MainUploadProbe')
run(java_path,'-cp',out,'MainReplyProbe')
run(java_path,'-cp',out,'AssistantEndpointTest')
print('Production delivery method checks passed; Android UI/device behavior is outside this JVM harness.')
