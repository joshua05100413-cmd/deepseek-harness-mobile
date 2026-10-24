package com.dsh.remote;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Compact portrait splash: product and one inseparable creator lockup. */
public class BootAnimView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Bitmap icon, avatar;
    private final Drawable bili;
    private final int accent;
    private ValueAnimator animator;
    private float t;
    private final long started = android.os.SystemClock.elapsedRealtime();
    private String status = "正在连接你的工作台…";

    public BootAnimView(Context context) {
        super(context);
        icon = BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher);
        avatar = BitmapFactory.decodeResource(getResources(), R.drawable.avatar);
        bili = getResources().getDrawable(R.drawable.ic_bilibili, null).mutate();
        accent = ThemePalette.get(context).accent;
        setContentDescription("DSHgo，作者：哔哩哔哩 FA 2517。正在连接工作台");
    }
    public void setStatus(String value) {
        if (value != null && !value.equals(status)) { status=value; postInvalidate(); }
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animator=ValueAnimator.ofFloat(0,1); animator.setDuration(1200);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(a->{t=(android.os.SystemClock.elapsedRealtime()-started)/1000f;invalidate();});
        animator.start();
    }
    @Override protected void onDetachedFromWindow() {
        if(animator!=null){animator.cancel();animator=null;}
        super.onDetachedFromWindow();
    }
    private static float ease(float time,float start,float end){
        float p=Math.min(1,Math.max(0,(time-start)/(end-start)));return 1-(1-p)*(1-p)*(1-p);
    }
    private void text(Canvas c,String value,float x,float y,float size,int color,boolean bold) {
        paint.setShader(null);paint.setAlpha(255);paint.setColor(color);paint.setTextSize(size);
        paint.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.CENTER);c.drawText(value,x,y,paint);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w=getWidth(),h=getHeight();if(w==0||h==0)return;
        float d=getResources().getDisplayMetrics().density,cx=w/2f,cy=h*.36f;
        // A soft illuminated blue surface also replaces the old black splash
        // when the workbench itself uses a dark theme.
        paint.setShader(new LinearGradient(0,0,w,h,0xFFF7FAFF,0xFFE5EFFB,Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,w,h,paint);paint.setShader(null);
        paint.setColor(0x12628AD1);canvas.drawCircle(w*.88f,h*.18f,112*d,paint);
        paint.setColor(0x0C628AD1);canvas.drawCircle(w*.08f,h*.78f,150*d,paint);
        float pIn=ease(t,0,1.1f),pSlide=ease(t,1.5f,2.9f),pName=ease(t,2.4f,3.6f),pAuthor=ease(t,3.1f,4.5f);
        float iconX=cx-76*d*pSlide;
        paint.setColor(Color.WHITE);paint.setAlpha((int)(255*pIn));
        canvas.drawRoundRect(new RectF(iconX-46*d,cy-46*d,iconX+46*d,cy+46*d),25*d,25*d,paint);
        if(icon!=null)canvas.drawBitmap(icon,null,new RectF(iconX-39*d,cy-39*d,iconX+39*d,cy+39*d),paint);
        canvas.saveLayerAlpha(0,0,w,h,(int)(255*pName));
        text(canvas,"DSHgo",cx+47*d+(1-pName)*140*d,cy+11*d,30*d,0xFF173252,true);
        canvas.restore();
        canvas.saveLayerAlpha(0,0,w,h,(int)(255*pName));
        text(canvas,"随身连接你的工作台",cx+(1-pName)*70*d,cy+88*d,13*d,0xFF59718C,false);
        canvas.restore();

        // One right-to-left author lockup: platform, avatar and creator name
        // share exactly the same progress, translation and alpha.
        float authorY=cy+144*d;
        canvas.saveLayerAlpha(0,0,w,h,(int)(255*pAuthor));
        canvas.translate((1-pAuthor)*130*d,0);
        paint.setColor(0xCFFFFFFF);paint.setAlpha(255);
        canvas.drawRoundRect(new RectF(cx-118*d,authorY-24*d,cx+118*d,authorY+24*d),24*d,24*d,paint);
        if(avatar!=null){canvas.save();Path circle=new Path();circle.addCircle(cx-91*d,authorY,14*d,Path.Direction.CW);canvas.clipPath(circle);canvas.drawBitmap(avatar,null,new RectF(cx-105*d,authorY-14*d,cx-77*d,authorY+14*d),paint);canvas.restore();}
        bili.setBounds((int)(cx-64*d),(int)(authorY-9*d),(int)(cx-46*d),(int)(authorY+9*d));bili.setAlpha(255);bili.draw(canvas);
        text(canvas,"哔哩哔哩 · FA 2517",cx+25*d,authorY+5*d,14*d,0xFF294665,true);
        canvas.restore();
        float statusY=Math.min(h-140*d,authorY+91*d);
        paint.setColor(accent);paint.setAlpha((int)(135+100*(.5+.5*Math.sin(t*3))));
        canvas.drawCircle(cx,statusY-26*d,3*d,paint);
        paint.setTextSize(13*d);
        String line=android.text.TextUtils.ellipsize(status,new android.text.TextPaint(paint),w-48*d,android.text.TextUtils.TruncateAt.END).toString();
        text(canvas,line,cx,statusY,13*d,0xFF59718C,false);
        text(canvas,"DSHgo  /  手机远程工作台",cx,h-52*d,11*d,0xFF71869E,false);
    }
}
