package com.tiam.challenge.service;

import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.repository.ChallengePurchaseRepository;

/**
 * Which token goes in the link a buyer is sent. A buyer has ONE link for the whole Desafío: a month bought from the
 * player is its own purchase (own token, only used by the player to keep that month's progress) chained to the first
 * purchase, and everything the buyer receives (access email, WhatsApp reply) carries the first purchase's token.
 */
final class ChallengeLinkTokens {

    private ChallengeLinkTokens() {
    }

    /**
     * The first purchase's token for a month bought from the player, otherwise the purchase's own token. If the first
     * purchase is gone (it should never be), the purchase's own token still opens that month.
     */
    static String of(ChallengePurchase purchase, ChallengePurchaseRepository repository) {
        if (purchase.getParentPurchaseId() == null) {
            return purchase.getAccessToken();
        }
        // A deactivated first purchase (a refunded pack order) opens nothing: never send a buyer to it.
        return repository.findById(purchase.getParentPurchaseId())
                .filter(ChallengePurchase::isActivo)
                .map(ChallengePurchase::getAccessToken)
                .orElse(purchase.getAccessToken());
    }
}
