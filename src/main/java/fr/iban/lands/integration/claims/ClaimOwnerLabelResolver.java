package fr.iban.lands.integration.claims;

import fr.iban.lands.model.land.Land;

@FunctionalInterface
public interface ClaimOwnerLabelResolver {

    String resolve(Land land);
}
