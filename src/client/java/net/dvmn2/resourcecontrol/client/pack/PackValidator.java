package net.dvmn2.resourcecontrol.client.pack;

import net.dvmn2.resourcecontrol.client.network.ResourceControlProtocol;
import net.dvmn2.resourcecontrol.client.network.SyncPayload;
import net.dvmn2.resourcecontrol.client.network.SyncPayload.PackEntry;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks a pack set received from the server before anything is downloaded.
 */
public final class PackValidator {

    private static final int MAX_URL_LENGTH = 512;
    private static final int MIN_PRIORITY = -1000;
    private static final int MAX_PRIORITY = 1000;

    private PackValidator() {
    }

    /**
     * Validates every entry and clamps priorities into the allowed range.
     *
     * @param limit largest pack size the player approved
     * @throws IllegalArgumentException with a short English reason when the set is not acceptable
     */
    public static List<PackEntry> validate(SyncPayload payload, long limit) {
        if (payload.protocol() != ResourceControlProtocol.PROTOCOL) {
            throw new IllegalArgumentException("unsupported protocol " + payload.protocol());
        }
        Set<String> names = new HashSet<>();
        List<PackEntry> result = new ArrayList<>(payload.packs().size());
        for (PackEntry entry : payload.packs()) {
            if (!PackCache.isValidName(entry.name())) {
                throw new IllegalArgumentException("invalid pack name");
            }
            if (!names.add(entry.name())) {
                throw new IllegalArgumentException("duplicate pack " + entry.name());
            }
            if (!PackCache.isValidSha1(entry.sha1())) {
                throw new IllegalArgumentException("invalid sha1 for " + entry.name());
            }
            if (entry.size() <= 0 || entry.size() > limit || entry.size() > PackCache.MAX_PACK_SIZE) {
                throw new IllegalArgumentException("invalid size for " + entry.name());
            }
            if (!isValidUrl(entry.url())) {
                throw new IllegalArgumentException("url must be https for " + entry.name());
            }
            int priority = Math.max(MIN_PRIORITY, Math.min(MAX_PRIORITY, entry.priority()));
            result.add(new PackEntry(entry.name(), entry.url(), entry.sha1(), entry.size(), priority,
                    entry.persistent()));
        }
        return result;
    }

    private static boolean isValidUrl(String url) {
        if (url.length() > MAX_URL_LENGTH) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
