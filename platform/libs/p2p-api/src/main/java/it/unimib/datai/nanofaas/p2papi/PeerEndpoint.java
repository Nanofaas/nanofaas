package it.unimib.datai.nanofaas.p2papi;

import java.net.URI;
import java.util.Objects;
public record PeerEndpoint(String peerId, String incarnation, URI invocationUri) {
    public PeerEndpoint {
        if (peerId == null || peerId.isBlank() || incarnation == null || incarnation.isBlank())
            throw new IllegalArgumentException("peer identity and incarnation are required");
        Objects.requireNonNull(invocationUri);
        if (!("http".equals(invocationUri.getScheme()) || "https".equals(invocationUri.getScheme()))
                || invocationUri.getHost() == null || invocationUri.getUserInfo() != null
                || invocationUri.getFragment() != null || invocationUri.getQuery() != null)
            throw new IllegalArgumentException("explicit HTTP invocation endpoint required");
    }
}
