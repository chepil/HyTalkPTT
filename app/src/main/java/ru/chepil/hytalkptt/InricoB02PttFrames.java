package ru.chepil.hytalkptt;

/**
 * SPP PTT frames for Inrico B02PTT-FF01 (and compatible): fixed-length ASCII.
 */
public final class InricoB02PttFrames {

    /** {@code +PTT=P} — PTT pressed. */
    public static final byte[] PTT_PRESS = new byte[] {
            0x2B, 0x50, 0x54, 0x54, 0x3D, 0x50
    };

    /** {@code +PTT=R} — PTT released. */
    public static final byte[] PTT_RELEASE = new byte[] {
            0x2B, 0x50, 0x54, 0x54, 0x3D, 0x52
    };

    public static final int FRAME_LEN = 6;

    /** Name prefix (full name may be {@code B02PTT-FF01}). */
    public static final String DEFAULT_NAME_PREFIX = "B02PTT";

    private InricoB02PttFrames() {}
}
