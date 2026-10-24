package com.letseries.letmelook.client.state;

import com.letseries.letmelook.client.timeline.ReplayTimeline;
import com.letseries.letmelook.common.LookupStatus;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Client-side home for server {@link LookupStatus} acknowledgements.
 *
 * <p>W2 dropped rejected requests on the server log, so {@code /lm lookup} on a
 * denied/throttled/failed request left the client waiting with no explanation.
 * Every terminal status now reaches the player as a chat line, is retained as
 * the {@link #last()} status for the HUD and {@code /lm status}, and is used to
 * settle the {@link ReplayTimeline} so {@code isComplete()} stops lying.
 */
public final class LookupStatusTracker {
    private static final int LOG_CAP = 16;

    private final ReplayTimeline timeline;
    private final Deque<String> recent = new ArrayDeque<>();
    private LookupStatus last;

    public LookupStatusTracker(ReplayTimeline timeline) {
        this.timeline = timeline;
    }

    /** Called on the client thread. */
    public void accept(LookupStatus status) {
        if (status == null) {
            return;
        }
        this.last = status;
        remember(describe(status));
        if (timeline != null && status.isTerminal()) {
            timeline.markSettled(status.requestId);
        }
        post(status);
    }

    public LookupStatus last() {
        return last;
    }

    /** Most recent messages, newest first. */
    public List<String> recent() {
        return new ArrayList<>(recent);
    }

    /** One-line summary for the HUD / command feedback. */
    public String summary() {
        LookupStatus s = last;
        if (s == null) {
            return "no server response yet";
        }
        StringBuilder sb = new StringBuilder(s.state.name().toLowerCase(Locale.ROOT));
        sb.append(" req=").append(s.requestId);
        if (!s.reason.isEmpty()) {
            sb.append(" — ").append(s.reason);
        }
        if (s.state == LookupStatus.State.DONE) {
            sb.append(" (events=").append(s.total).append(" pages=").append(s.pages)
                    .append(" took=").append(s.tookMs).append("ms")
                    .append(s.truncated ? ", TRUNCATED" : "").append(')');
        }
        if (s.retryAfterMs > 0) {
            sb.append(" retry in ").append(s.retryAfterMs).append("ms");
        }
        return sb.toString();
    }

    private void remember(String line) {
        recent.addFirst(line);
        while (recent.size() > LOG_CAP) {
            recent.removeLast();
        }
    }

    private static String describe(LookupStatus s) {
        return switch (s.state) {
            case ACCEPTED -> "req=" + s.requestId + " accepted, scanning…";
            case DENIED -> "req=" + s.requestId + " denied: " + s.reason;
            case THROTTLED -> "req=" + s.requestId + " throttled: " + s.reason
                    + (s.retryAfterMs > 0 ? " (retry in " + s.retryAfterMs + "ms)" : "");
            case ERROR -> "req=" + s.requestId + " failed: " + s.reason;
            case DONE -> "req=" + s.requestId + " done: " + s.total + " events in "
                    + s.pages + " page(s), " + s.tookMs + "ms"
                    + (s.truncated ? " (TRUNCATED at server cap)" : "");
        };
    }

    private static void post(LookupStatus s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud == null) {
            return;
        }
        Formatting color = switch (s.state) {
            case ACCEPTED -> Formatting.GRAY;
            case DONE -> s.truncated ? Formatting.GOLD : Formatting.GREEN;
            case THROTTLED -> Formatting.YELLOW;
            case DENIED, ERROR -> Formatting.RED;
        };
        Text line = Text.literal("[LetMeLook] ").formatted(Formatting.AQUA)
                .append(Text.literal(describe(s)).formatted(color));
        if (s.state == LookupStatus.State.ACCEPTED) {
            // Acknowledge quietly: the chat log is reserved for outcomes.
            if (mc.player != null) {
                mc.player.sendMessage(line, true);
            }
            return;
        }
        mc.inGameHud.getChatHud().addMessage(line);
    }
}
