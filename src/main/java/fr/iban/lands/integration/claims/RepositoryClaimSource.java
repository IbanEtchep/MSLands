package fr.iban.lands.integration.claims;

import fr.iban.lands.api.LandRepository;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.Land;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

public final class RepositoryClaimSource implements ClaimSource {

    private final LandRepository repository;

    public RepositoryClaimSource(LandRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public Map<SChunk, Land> claims() {
        return repository.getChunks();
    }

    @Override
    public Land landAt(SChunk chunk) {
        return repository.getLandAt(chunk);
    }

    @Override
    public Collection<SChunk> chunks(Land land) {
        return repository.getChunks(land);
    }

    @Override
    public boolean isWilderness(Land land) {
        return repository.isWilderness(land);
    }
}
