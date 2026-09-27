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
 * Receives paged {@link TimelineChunk}s on {@code letmelook:lookup}
 * (Fabric CustomPayload, interoperable with Bukkit PluginMessage)
 * and feeds them into the {@link GhostLayer}.
 */
public final class TimelineReceiver {

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

    private TimelineReceiver() {}

    public static void register(GhostLayer ghostLayer) {
        PayloadTypeRegistry.playS2C().register(TimelinePayload.ID, TimelinePayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(TimelinePayload.ID, (payload, context) -> {
            TimelineChunk chunk;
            try {
                chunk = Codec.decode(payload.data(), TimelineChunk.class);
            } catch (Exception e) {
                return;
            }
            context.client().execute(() -> ghostLayer.ingest(chunk));
        });
    }
}
