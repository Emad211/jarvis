package ir.emad.jarvis;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

/**
 * Just enough Markdown to keep answers readable: **bold**, *italic*, `code` and fenced
 * blocks. A full parser is not worth the dependency; the model mostly uses these four.
 */
final class Md {

    private Md() {
    }

    static CharSequence render(String src, int codeColor, int codeBg) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] blocks = src.split("```", -1);
        for (int i = 0; i < blocks.length; i++) {
            if (i % 2 == 1) {
                // fenced block: strip the language line, keep it monospace
                String code = blocks[i];
                int nl = code.indexOf('\n');
                if (nl >= 0 && nl < 20 && !code.substring(0, nl).contains(" ")) {
                    code = code.substring(nl + 1);
                }
                int start = out.length();
                out.append(code.trim());
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(codeColor), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(codeBg), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                inline(out, blocks[i], codeColor, codeBg);
            }
        }
        return out;
    }

    private static void inline(SpannableStringBuilder out, String text, int codeColor, int codeBg) {
        int i = 0;
        while (i < text.length()) {
            int bold = text.indexOf("**", i);
            int code = text.indexOf('`', i);
            int next = min(bold, code);
            if (next < 0) {
                out.append(text, i, text.length());
                return;
            }
            out.append(text, i, next);
            if (next == code) {
                int close = text.indexOf('`', next + 1);
                if (close < 0) {
                    out.append(text, next, text.length());
                    return;
                }
                int start = out.length();
                out.append(text, next + 1, close);
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(codeColor), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(codeBg), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = close + 1;
            } else {
                int close = text.indexOf("**", next + 2);
                if (close < 0) {
                    out.append(text, next, text.length());
                    return;
                }
                int start = out.length();
                out.append(text, next + 2, close);
                out.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = close + 2;
            }
        }
    }

    private static int min(int a, int b) {
        if (a < 0) {
            return b;
        }
        if (b < 0) {
            return a;
        }
        return Math.min(a, b);
    }
}
