package net.dvmn2.resourcecontrol.client.network;

/**
 * Protocol constants shared by all ResourceControl payloads. Must match the plugin's
 * {@code SyncManager.PROTOCOL}.
 */
public final class ResourceControlProtocol {

    /**
     * Protocol version; increased on every change of a packet format.
     */
    public static final int PROTOCOL = 2;

    /**
     * Protocol value of a packet that could not be decoded; receivers ignore it.
     */
    public static final int INVALID_PROTOCOL = -1;

    private ResourceControlProtocol() {
    }
}
