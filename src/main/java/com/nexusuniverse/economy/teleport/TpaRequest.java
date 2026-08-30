package com.nexusuniverse.economy.teleport;

import java.util.UUID;

/**
 * One pending "please teleport me to you" request. Keyed by target in
 * {@link TpaManager} -- a new request to the same target overwrites an
 * older one (the previous requester is told they were superseded).
 */
public record TpaRequest(UUID requesterId, String requesterName, UUID targetId, long expiresAtMillis) {

    public boolean isExpired(long nowMillis) {
        return nowMillis >= expiresAtMillis;
    }
}
