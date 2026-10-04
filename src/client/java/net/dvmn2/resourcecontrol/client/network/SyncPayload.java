package net.dvmn2.resourcecontrol.client.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Payload от плагина (канал {@code dvmn2:rc_sync}): ПОЛНЫЙ желаемый набор паков игрока.
 * Каждое новое сообщение заменяет предыдущее.
 * <p>
 * Формат:
 * <pre>
 * VarInt  protocol
 * Boolean allowShaders
 * VarInt  count
 * count x { String name, String url, String sha1, VarLong size, VarInt priority, Boolean persistent }
 * </pre>
 * Строка = VarInt длина в байтах + UTF-8 (стандартный PacketByteBuf#readString).
 * Больший priority — пак лежит выше (перекрывает остальные).
 */
public record SyncPayload(int protocol, boolean allowShaders, List<PackEntry> packs) implements CustomPayload {

    public static final CustomPayload.Id<SyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of("dvmn2", "rc_sync"));

    /**
     * Защита от испорченного/вредоносного пакета с огромным count.
     */
    public static final int MAX_PACKS = 64;

    public static final PacketCodec<PacketByteBuf, SyncPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeVarInt(payload.protocol());
                buf.writeBoolean(payload.allowShaders());
                buf.writeVarInt(payload.packs().size());
                for (PackEntry e : payload.packs()) {
                    buf.writeString(e.name());
                    buf.writeString(e.url());
                    buf.writeString(e.sha1());
                    buf.writeVarLong(e.size());
                    buf.writeVarInt(e.priority());
                    buf.writeBoolean(e.persistent());
                }
            },
            buf -> {
                int protocol = buf.readVarInt();
                boolean allowShaders = buf.readBoolean();
                int count = buf.readVarInt();
                if (count < 0 || count > MAX_PACKS) {
                    throw new IllegalArgumentException("rc_sync: недопустимое количество паков: " + count);
                }
                List<PackEntry> packs = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    String name = buf.readString(64);
                    String url = buf.readString(1024);
                    String sha1 = buf.readString(64);
                    long size = buf.readVarLong();
                    int priority = buf.readVarInt();
                    boolean persistent = buf.readBoolean();
                    packs.add(new PackEntry(name, url, sha1, size, priority, persistent));
                }
                return new SyncPayload(protocol, allowShaders, List.copyOf(packs));
            }
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    /**
     * Один пак из желаемого набора.
     */
    public record PackEntry(String name, String url, String sha1, long size, int priority, boolean persistent) {
    }
}
