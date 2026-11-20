package ru.oldest.bionicpocket;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;

/** Validates assistant endpoints and confines office HTTP to private LAN addresses. */
public final class AssistantEndpoint {
    public static final String GONETS = "gonets";
    public static final String DOMOVOY = "domovoy";

    /** This validation policy has no mutable instances. */
    private AssistantEndpoint() {}

    /** Validate a server origin without accepting credentials, paths or a remote HTTP endpoint. */
    public static String validate(String assistant, String value) throws IOException {
        if (!GONETS.equals(assistant) && !DOMOVOY.equals(assistant)) throw new IOException("Unknown assistant");
        try {
            URI uri = new URI(value.trim());
            boolean office = DOMOVOY.equals(assistant);
            if (!("https".equals(uri.getScheme()) || office && "http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                    || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IOException(office ? "Use an office LAN server origin" : "Gonets requires an HTTPS server origin");
            }
            return value.trim().replaceAll("/+$", "");
        } catch (java.net.URISyntaxException error) {
            throw new IOException("Invalid server address", error);
        }
    }

    /** Reject public, loopback and link-local destinations for the office-only assistant. */
    public static void requireOfficeAddress(String assistant, String base) throws IOException {
        if (!DOMOVOY.equals(assistant)) return;
        try {
            for (InetAddress address : InetAddress.getAllByName(new URI(base).getHost())) {
                if (!isPrivateAddress(address.getAddress())) throw new IOException("Domovoy is available only on the office LAN");
            }
        } catch (java.net.URISyntaxException error) {
            throw new IOException("Invalid office server address", error);
        }
    }

    /** Recognize RFC1918 IPv4 and unique-local IPv6 addresses, without DNS or network I/O. */
    public static boolean isPrivateAddress(byte[] address) {
        if (address.length == 4) {
            int first = address[0] & 255, second = address[1] & 255;
            return first == 10 || first == 172 && second >= 16 && second <= 31 || first == 192 && second == 168;
        }
        return address.length == 16 && (address[0] & 254) == 252;
    }
}
