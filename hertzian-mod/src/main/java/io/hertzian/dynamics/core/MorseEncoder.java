package io.hertzian.dynamics.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Morse (CW) encoder. Turns plain text into a sample-accurate keying
 * timeline a telegraph key feeds to a CW transmitter, and into a
 * dot/dash preview string for the GUI.
 *
 * <p>
 * Why it lives in {@code core}
 * It is the transmit-side counterpart of {@link MorseDecoder}: pure DSP
 * with no Minecraft dependency, callable from both the server tile (to
 * generate the keying stream) and the client GUI (to preview the typed
 * message), and unit testable on its own.
 *
 * <p>
 * Timing
 * The unit is the dot length, derived from words per minute by the
 * standard PARIS convention: dot milliseconds equal 1200 divided by WPM.
 * From that one unit the classic ratios follow: a dot is one unit on, a
 * dash three units on, the gap between elements of one character is one
 * unit off, the gap between characters is three units off, and the gap
 * between words is seven units off. The decoder on the other end expects
 * exactly these ratios, so a message keyed here reads back cleanly when
 * the link is good and degrades into question marks as the signal to
 * noise ratio falls, the same way a real operator's copy does.
 */
public final class MorseEncoder {

    private static final Map<Character, String> TABLE = new HashMap<>();
    static {
        put('A', ".-");
        put('B', "-...");
        put('C', "-.-.");
        put('D', "-..");
        put('E', ".");
        put('F', "..-.");
        put('G', "--.");
        put('H', "....");
        put('I', "..");
        put('J', ".---");
        put('K', "-.-");
        put('L', ".-..");
        put('M', "--");
        put('N', "-.");
        put('O', "---");
        put('P', ".--.");
        put('Q', "--.-");
        put('R', ".-.");
        put('S', "...");
        put('T', "-");
        put('U', "..-");
        put('V', "...-");
        put('W', ".--");
        put('X', "-..-");
        put('Y', "-.--");
        put('Z', "--..");
        put('0', "-----");
        put('1', ".----");
        put('2', "..---");
        put('3', "...--");
        put('4', "....-");
        put('5', ".....");
        put('6', "-....");
        put('7', "--...");
        put('8', "---..");
        put('9', "----.");
        put('.', ".-.-.-");
        put(',', "--..--");
        put('?', "..--..");
        put('/', "-..-.");
        put('=', "-...-");
        put('+', ".-.-.");
        put('-', "-....-");
        put(':', "---...");
        put('@', ".--.-.");
    }

    private static void put(char c, String code) {
        TABLE.put(c, code);
    }

    private MorseEncoder() {}

    /**
     * Encode {@code text} into a keying timeline. Each entry is a two
     * element array {@code {state, samples}} where {@code state} is 1 for
     * key down (carrier on) and 0 for key up (carrier off), and
     * {@code samples} is the duration of that segment. Consecutive
     * segments alternate on and off. {@code dotSamples} is the dot unit
     * length in samples, from which all other durations are derived.
     *
     * <p>
     * Unknown characters are skipped silently rather than keyed as an
     * error symbol, so a stray byte in the input does not put garbage on
     * air. Letters are upper-cased because Morse has no case.
     */
    public static List<int[]> encode(String text, double dotSamples) {
        List<int[]> out = new ArrayList<>();
        if (text == null) return out;
        int unit = Math.max(1, (int) Math.round(dotSamples));
        String upper = text.toUpperCase();

        boolean firstCharOfMessage = true;
        for (int wi = 0; wi < upper.length(); wi++) {
            char c = upper.charAt(wi);
            if (c == ' ') {
                // Word gap. Replace any trailing character gap so the
                // total off time between the previous character and the
                // next word is exactly seven units, not three plus seven.
                replaceTrailingGap(out, 7 * unit);
                firstCharOfMessage = false;
                continue;
            }
            String code = TABLE.get(c);
            if (code == null) continue;

            if (!firstCharOfMessage) {
                // Character gap before this character, unless the previous
                // entry already set a longer (word) gap.
                ensureGapAtLeast(out, 3 * unit);
            }
            firstCharOfMessage = false;

            for (int ei = 0; ei < code.length(); ei++) {
                int onLen = code.charAt(ei) == '-' ? 3 * unit : unit;
                out.add(new int[] { 1, onLen });
                if (ei < code.length() - 1) {
                    // Element gap inside the character.
                    out.add(new int[] { 0, unit });
                }
            }
        }
        return out;
    }

    /** If the timeline ends on an off segment, raise it to at least len. */
    private static void ensureGapAtLeast(List<int[]> out, int len) {
        if (out.isEmpty()) return;
        int[] last = out.get(out.size() - 1);
        if (last[0] == 0) {
            if (last[1] < len) last[1] = len;
        } else {
            out.add(new int[] { 0, len });
        }
    }

    /** Force the trailing gap to exactly len, appending one if needed. */
    private static void replaceTrailingGap(List<int[]> out, int len) {
        if (out.isEmpty()) return;
        int[] last = out.get(out.size() - 1);
        if (last[0] == 0) last[1] = len;
        else out.add(new int[] { 0, len });
    }

    /**
     * Dot/dash preview of a message, spaces between characters and a
     * slash between words. For the GUI so the operator sees what will go
     * out before pressing send.
     */
    public static String toMorseString(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        String upper = text.toUpperCase();
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (c == ' ') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
                    sb.setCharAt(sb.length() - 1, '/');
                }
                sb.append(' ');
                continue;
            }
            String code = TABLE.get(c);
            if (code == null) continue;
            sb.append(code)
                .append(' ');
        }
        return sb.toString()
            .trim();
    }
}
