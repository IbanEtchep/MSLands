package fr.iban.lands.integration.claims;

public interface ClaimMarkerSink extends AutoCloseable {

    void clear();

    void put(ClaimMarkerDescriptor marker);

    void remove(String world, String markerId);

    @Override
    void close();
}
