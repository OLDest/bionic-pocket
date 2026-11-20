import java.io.IOException;
import ru.oldest.bionicpocket.AssistantEndpoint;
/** Exercise actual assistant URL and office-address policy without network requests. */
public final class AssistantEndpointTest {
  /** Fail with the invariant that did not hold. */
  private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  /** Verify a forbidden origin is rejected before a connection can be made. */
  private static void rejects(String id,String url)throws Exception{try{AssistantEndpoint.validate(id,url);throw new AssertionError("accepted "+url);}catch(IOException expected){}}
  /** Cover both assistants, malformed origins and private IPv4/IPv6 boundaries. */
  public static void main(String[] args)throws Exception{
    check(AssistantEndpoint.validate("gonets","https://example.org/").equals("https://example.org"),"HTTPS origin");
    check(AssistantEndpoint.validate("domovoy","http://192.168.1.10:1236").equals("http://192.168.1.10:1236"),"office HTTP");
    rejects("gonets","http://192.168.1.10:1236");rejects("gonets","https://user:secret@example.org");
    rejects("domovoy","http://192.168.1.10/path");rejects("domovoy","http://192.168.1.10?token=not-a-real-secret");
    rejects("unknown","https://example.org");rejects("domovoy","");
    check(AssistantEndpoint.isPrivateAddress(new byte[]{10,1,2,3}),"10/8");
    check(AssistantEndpoint.isPrivateAddress(new byte[]{(byte)172,16,0,1}),"172.16/12 lower");
    check(AssistantEndpoint.isPrivateAddress(new byte[]{(byte)172,31,0,1}),"172.16/12 upper");
    check(!AssistantEndpoint.isPrivateAddress(new byte[]{(byte)172,32,0,1}),"172.32 public");
    check(AssistantEndpoint.isPrivateAddress(new byte[]{(byte)192,(byte)168,1,1}),"192.168/16");
    check(!AssistantEndpoint.isPrivateAddress(new byte[]{127,0,0,1}),"loopback");
    check(!AssistantEndpoint.isPrivateAddress(new byte[]{(byte)169,(byte)254,1,1}),"link local");
    check(!AssistantEndpoint.isPrivateAddress(new byte[]{8,8,8,8}),"public IPv4");
    byte[] ula=new byte[16];ula[0]=(byte)253;check(AssistantEndpoint.isPrivateAddress(ula),"IPv6 ULA");
    byte[] public6=new byte[16];public6[0]=32;check(!AssistantEndpoint.isPrivateAddress(public6),"public IPv6");
    AssistantEndpoint.requireOfficeAddress("domovoy","http://192.168.1.10:1236");
    try{AssistantEndpoint.requireOfficeAddress("domovoy","http://203.0.113.1:1236");throw new AssertionError("public office destination");}catch(IOException expected){}
    System.out.println("PASS: real endpoint policy, shared-token destinations, office HTTP and private-address boundaries");
  }
}
