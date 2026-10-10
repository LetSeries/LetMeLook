package com.letseries.letmelook.client.net;

import com.letseries.letmelook.client.render.GhostLayer;
import com.letseries.letmelook.common.Codec;
import com.letseries.letmelook.common.TimelineChunk;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * W2 networking: bidirectional {@code letmelook:lookup} payloads.
 *
 * <ul>
 *   <li>S2C: gzipped-or-plain {@link TimelineChunk} pages (adaptive framing,
 *       auto-detected via the gzip magic header; W1 plain JSON still works).</li>
 *   <li>C2S: JSON {@code LookupRequest} bytes sent via
 *       {@link ClientPlayNetworking#send(CustomPayload)} (Bukkit PluginMessage
 *       compatible: the client payload bytes land on the server's
 *       {@code PluginMessageListener} for the same channel).</li>
 * </ul>
 */
public final class NetChannels {

    /** Must match the Bukkit PluginMessage channel {@code letmelook:lookup}. */
    public record TimelinePayload(byte[] data) implements CustomPayload {
        public static final CustomPayload.Id<TimelinePayload> ID =
                new CustomPayload.Id<>(Identifier.of("letmelook", "lookup"));
        public static final PacketCodec<RegistryByteBuf, TimelinePayload> CODEC =
                PacketCodec.of(
                        (payload, buf) -> buf.writeBytes(payload.data),
                        buf -> {
                            byte[] b = new byte[buf.readableBytes()];
                            buf.readBytes(b);
                            return new TimelinePayload(b);
                        });

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** C2S lookup request payload (raw JSON bytes, same channel). */
    public record LookupPayload(byte[] data) implements CustomPayload {
        public static final CustomPayload.Id<LookupPayload> ID =
                new CustomPayload.Id<>(Identifier.of("letmelook", "lookup"));
        public static final PacketCodec<RegistryByteBuf, LookupPayload> CODEC =
                PacketCodec.of(
                        (payload, buf) -> buf.writeBytes(payload.data),
                        buf -> {
                            byte[] b = new byte[buf.readableBytes()];
                            buf.readBytes(b);
                            return new LookupPayload(b);
                        });

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    private NetChannels() {}

    public static void register(GhostLayer ghostLayer) {
        PayloadTypeRegistry.playS2C().register(TimelinePayload.ID, TimelinePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(LookupPayload.ID, LookupPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(TimelinePayload.ID, (payload, context) -> {
            TimelineChunk chunk;
            try {
                chunk = Codec.decodeAdaptive(payload.data(), TimelineChunk.class);
            } catch (Exception e) {
                return;
            }
            context.client().execute(() -> ghostLayer.ingest(chunk));
        });
    }
}
