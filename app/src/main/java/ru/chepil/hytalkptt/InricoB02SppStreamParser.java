package ru.chepil.hytalkptt;

import java.util.ArrayList;

/**
 * Parses Inrico B02PTT RFCOMM stream: extracts {@code +PTT=P} / {@code +PTT=R} across read boundaries;
 * reports other byte runs for UI/debug (new headset protocols).
 */
public final class InricoB02SppStreamParser {

    public interface Listener {
        void onPttPressed();

        void onPttReleased();

        void onUnknownBytes(byte[] data);
    }

    private final Listener listener;
    private final ArrayList<Byte> pending = new ArrayList<Byte>();

    public InricoB02SppStreamParser(Listener listener) {
        this.listener = listener;
    }

    public void accept(byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            return;
        }
        for (byte b : chunk) {
            pending.add(b);
        }
        drain();
    }

    private void drain() {
        while (true) {
            int i = indexOfPttFrame();
            if (i < 0) {
                break;
            }
            Kind kind = frameKindAt(i);
            if (kind == null) {
                break;
            }
            if (i > 0) {
                emitUnknownRange(0, i);
                removeFirst(i);
                continue;
            }
            removeFirst(InricoB02PttFrames.FRAME_LEN);
            if (kind == Kind.PRESS) {
                listener.onPttPressed();
            } else {
                listener.onPttReleased();
            }
        }
        trimPending();
    }

    private void trimPending() {
        int keep = InricoB02PttFrames.FRAME_LEN - 1;
        while (pending.size() > keep) {
            int nDrop = pending.size() - keep;
            emitUnknownRange(0, nDrop);
            removeFirst(nDrop);
        }
    }

    private int indexOfPttFrame() {
        int n = pending.size();
        int need = InricoB02PttFrames.FRAME_LEN;
        if (n < need) {
            return -1;
        }
        for (int i = 0; i <= n - need; i++) {
            if (frameKindAt(i) != null) {
                return i;
            }
        }
        return -1;
    }

    private enum Kind {
        PRESS, RELEASE
    }

    private Kind frameKindAt(int i) {
        if (i + InricoB02PttFrames.FRAME_LEN > pending.size()) {
            return null;
        }
        if (matches(i, InricoB02PttFrames.PTT_PRESS)) {
            return Kind.PRESS;
        }
        if (matches(i, InricoB02PttFrames.PTT_RELEASE)) {
            return Kind.RELEASE;
        }
        return null;
    }

    private boolean matches(int offset, byte[] pattern) {
        for (int j = 0; j < pattern.length; j++) {
            if (pending.get(offset + j) != pattern[j]) {
                return false;
            }
        }
        return true;
    }

    private void emitUnknownRange(int from, int toExclusive) {
        int len = toExclusive - from;
        if (len <= 0) {
            return;
        }
        byte[] out = new byte[len];
        for (int k = 0; k < len; k++) {
            out[k] = pending.get(from + k);
        }
        listener.onUnknownBytes(out);
    }

    private void removeFirst(int n) {
        for (int k = 0; k < n; k++) {
            if (!pending.isEmpty()) {
                pending.remove(0);
            }
        }
    }
}
