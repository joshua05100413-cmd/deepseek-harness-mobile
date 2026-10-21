package com.dsh.remote;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * DSHgo boot animation. The DSH icon lands at the center, then pulls to the
 * left and drags everything out after it — the DSHgo wordmark, the author
 * avatar (small, in sync with the icon), the Fa2517 name and the pink bilibili
 * mark below it. Every slide runs right-to-left. The status line starts as
 * "正在载入 DSH…" so the connecting window is never a blank curtain, and the
 * whole layer cross-fades into the workbench once the page is ready.
 */
public class BootAnimView extends View {

    // choreography beats, seconds since start
    private static final float ICON_IN_B = 1.1f;   // icon settles at center
    private static final float SLIDE_A = 1.5f, SLIDE_B = 2.9f;   // icon+avatar pull left
    private static final float NAME_A = 2.4f, NAME_B = 3.6f;     // DSHgo pulled out
    private static final float FA_A = 3.1f, FA_B = 4.3f;         // Fa2517 pulled out
    private static final float BILI_A = 3.8f, BILI_B = 5.0f;     // bilibili mark pulled out
    private static final float FOOT_A = 4.5f, FOOT_B = 5.6f;     // support note

    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pinkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint avatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();

    private final Bitmap icon;
    private final Bitmap avatar;
    private final Drawable biliMark;

    private ValueAnimator animator;
    private float t = 0f;
    private String status = "正在载入 DSH…";
    private long startMs;

    public BootAnimView(Context context) {
        super(context);
        icon = BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher);
        avatar = BitmapFactory.decodeResource(getResources(), R.drawable.avatar);
        biliMark = getResources().getDrawable(R.drawable.ic_bilibili);
        bgPaint.setColor(0xFF0E0F1A);
        textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.LEFT);
        dimPaint.setColor(0xFF8A90A6);
        dimPaint.setTextAlign(Paint.Align.CENTER);
        pinkPaint.setColor(0xFFFB7299); // bilibili pink
        pinkPaint.setFakeBoldText(true);
        pinkPaint.setTextAlign(Paint.Align.LEFT);
        startMs = System.currentTimeMillis();
    }

    public void setStatus(String s) {
        if (s != null && !s.equals(status)) {
            status = s;
            postInvalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(1000);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    t = (System.currentTimeMillis() - startMs) / 1000f;
                    invalidate();
                }
            });
        }
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    // ---------------------------------------------------------------- helpers

    private static float seg(float t, float a, float b) {
        return Math.min(1f, Math.max(0f, (t - a) / (b - a)));
    }

    private static float easeOut(float u) {
        return 1f - (float) Math.pow(1f - u, 3);
    }

    private static float easeInOut(float u) {
        return u < 0.5f ? 4f * u * u * u
            : 1f - (float) Math.pow(-2f * u + 2f, 3) / 2f;
    }

    private static int withAlpha(int color, float a) {
        return (color & 0x00FFFFFF) | (((int) (255 * Math.min(1f, Math.max(0f, a)))) << 24);
    }

    // ------------------------------------------------------------------ draw

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        canvas.drawRect(0, 0, w, h, bgPaint);

        float d = getResources().getDisplayMetrics().density;
        float cx = w / 2f;
        float cy = h * 0.32f;
        float iconSize = 104 * d;

        // progress values for every beat
        float pIn = easeOut(seg(t, 0f, ICON_IN_B));
        float pSlide = easeInOut(seg(t, SLIDE_A, SLIDE_B));
        float pName = easeOut(seg(t, NAME_A, NAME_B));
        float pFa = easeOut(seg(t, FA_A, FA_B));
        float pBili = easeOut(seg(t, BILI_A, BILI_B));
        float pFoot = easeOut(seg(t, FOOT_A, FOOT_B));

        // ---- geometry: final lockup is [icon] DSHgo, centered as a pair
        textPaint.setTextSize(27 * d);
        float nameW = textPaint.measureText("DSHgo");
        float gap = 15 * d;
        float pairW = iconSize + gap + nameW;
        float iconCxEnd = cx - pairW / 2f + iconSize / 2f;
        float iconCx = cx + (iconCxEnd - cx) * pSlide;   // center → left (right-to-left pull)

        // ---- breathing halo behind the icon (subtle; no rings, no sparks)
        float breathe = 0.5f - 0.5f * (float) Math.cos(t * 1.6f);
        float haloR = iconSize * (0.78f + 0.12f * breathe);
        int glowAlpha = (int) ((22 + 40 * breathe) * pIn);
        haloPaint.setShader(new RadialGradient(iconCx, cy, haloR,
            new int[]{withAlpha(0x6A76FF, glowAlpha / 255f), 0x005865F2},
            new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(iconCx, cy, haloR, haloPaint);

        // ---- icon: fades/scales in at center, then slides left
        float iconScale = 0.9f + 0.1f * pIn;
        float half = iconSize * iconScale / 2f;
        if (icon != null) {
            canvas.save();
            Path clip = new Path();
            clip.addRoundRect(new RectF(iconCx - half, cy - half, iconCx + half, cy + half),
                22 * d * iconScale, 22 * d * iconScale, Path.Direction.CW);
            canvas.clipPath(clip);
            canvas.drawBitmap(icon, null,
                new RectF(iconCx - half, cy - half, iconCx + half, cy + half), null);
            canvas.restore();
        }

        // ---- wordmark "DSHgo": pulled out right-to-left, trailing the icon
        float nameSlide = (1f - pName) * 150 * d;
        textPaint.setColor(withAlpha(0xFFFFFFFF, pName));
        canvas.drawText("DSHgo", iconCx + iconSize / 2f + gap + nameSlide,
            cy + 10 * d, textPaint);

        // ---- author row: avatar (small, no scale-in) moves together with icon
        float avatarCy = cy + iconSize * 0.5f + 52 * d;
        float avatarR = 17 * d;
        if (avatar != null && pSlide > 0f) {
            BitmapShader sh = new BitmapShader(avatar, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float sc = (2 * avatarR) / Math.min(avatar.getWidth(), avatar.getHeight());
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(sc, sc);
            m.postTranslate(iconCx - avatar.getWidth() * sc / 2f,
                avatarCy - avatar.getHeight() * sc / 2f);
            sh.setLocalMatrix(m);
            avatarPaint.setShader(sh);
            avatarPaint.setAlpha((int) (255 * Math.min(1f, pSlide * 1.6f)));
            canvas.drawCircle(iconCx, avatarCy, avatarR, avatarPaint);
        }

        // ---- "Fa2517": pulled out right-to-left beside the avatar
        float faSlide = (1f - pFa) * 140 * d;
        textPaint.setTextSize(15.5f * d);
        textPaint.setColor(withAlpha(0xFFFFFFFF, pFa));
        float faX = iconCx + avatarR + 12 * d + faSlide;
        canvas.drawText("Fa2517", faX, avatarCy + 5.5f * d, textPaint);

        // ---- pink bilibili mark below the name, pulled out the same way
        float biliSlide = (1f - pBili) * 130 * d;
        float biliX = faX + biliSlide;
        float biliY = avatarCy + 22 * d;
        if (biliMark != null) {
            biliMark.setBounds((int) biliX, (int) biliY, (int) (biliX + 15 * d), (int) (biliY + 15 * d));
            biliMark.setAlpha((int) (255 * pBili));
            biliMark.draw(canvas);
        }
        pinkPaint.setTextSize(13 * d);
        pinkPaint.setColor(withAlpha(0xFFFB7299, pBili));
        canvas.drawText("bilibili", biliX + 19 * d, biliY + 12.5f * d, pinkPaint);

        // ---- status line: visible from the very start, "正在载入 DSH…"
        dimPaint.setTextSize(13.5f * d);
        dimPaint.setColor(withAlpha(0xFF8A90A6, easeOut(seg(t, 0.25f, 1.1f))));
        canvas.drawText(status, cx, avatarCy + 82 * d, dimPaint);

        // ---- support note at the bottom
        dimPaint.setTextSize(12 * d);
        dimPaint.setColor(withAlpha(0xFF8A90A6, pFoot));
        canvas.drawText("如有问题请在 bilibili 联系我", cx, h - 74 * d, dimPaint);
        canvas.drawText("GitHub 提交问题请在 B 站通知我一声", cx, h - 52 * d, dimPaint);
    }
}
