package fr.iban.lands.integration.claims;

import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.Land;

import java.util.Collection;
import java.util.Map;

public interface ClaimSource {

    Map<SChunk, Land> claims();

    Land landAt(SChunk chunk);

    Collection<SChunk> chunks(Land land);

    boolean isWilderness(Land land);
}
