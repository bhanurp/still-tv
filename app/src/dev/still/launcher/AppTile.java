package dev.still.launcher;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;

/** One native render node per app. Focus and drawing do not allocate styling objects. */
final class AppTile extends View {
  private final UiStyle style;
  private final String label;
  private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
  private final RectF image = new RectF(), source = new RectF();
  private final Matrix matrix = new Matrix();
  private final DecelerateInterpolator easing = new DecelerateInterpolator();
  private final int labelHeight;
  private Bitmap bitmap;
  private String shortLabel;
  private float baseline;

  AppTile(UiStyle style, String label, Bitmap bitmap) {
    super(style.context);
    this.style = style;
    this.label = label;
    text.setTypeface(style.font(500));
    text.setTextSize(style.sp(13));
    text.setTextAlign(Paint.Align.CENTER);
    text.setColor(style.muted);
    labelHeight = Math.max(style.dp(34), Math.round(text.getFontSpacing() + style.dp(12)));
    border.setStyle(Paint.Style.STROKE);
    border.setStrokeWidth(style.dp(2));
    border.setColor(style.focus);
    setFocusable(true);
    setFocusableInTouchMode(true);
    setClickable(true);
    setLongClickable(true);
    setContentDescription(label + ". Hold OK for options.");
    setArtwork(bitmap);
  }

  void setArtwork(Bitmap value) {
    bitmap = value;
    if (value != null)
      imagePaint.setShader(new BitmapShader(value, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
    else {
      imagePaint.setShader(null);
      imagePaint.setColor(style.panel);
    }
    positionArtwork();
    invalidate();
  }

  private void positionArtwork() {
    if (bitmap == null || image.width() <= 0) return;
    source.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
    matrix.setRectToRect(source, image, Matrix.ScaleToFit.FILL);
    imagePaint.getShader().setLocalMatrix(matrix);
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    int width = MeasureSpec.getSize(widthSpec);
    setMeasuredDimension(
        resolveSize(width, widthSpec), resolveSize(width * 9 / 16 + labelHeight, heightSpec));
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    image.set(style.dp(2), style.dp(2), w - style.dp(2), w * 9 / 16f - style.dp(2));
    baseline = image.bottom + style.dp(11) - text.ascent();
    shortLabel =
        TextUtils.ellipsize(label, text, w - style.dp(4), TextUtils.TruncateAt.END).toString();
    positionArtwork();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    canvas.drawRoundRect(image, style.dp(12), style.dp(12), imagePaint);
    if (isFocused()) canvas.drawRoundRect(image, style.dp(12), style.dp(12), border);
    canvas.drawText(shortLabel == null ? label : shortLabel, getWidth() / 2f, baseline, text);
    if (bitmap == null)
      canvas.drawText(
          shortLabel == null ? label : shortLabel,
          getWidth() / 2f,
          image.centerY() - (text.ascent() + text.descent()) / 2,
          text);
  }

  @Override
  protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previous) {
    super.onFocusChanged(focused, direction, previous);
    text.setColor(focused ? style.foreground : style.muted);
    invalidate();
    animate().cancel();
    float scale = focused ? 1.04f : 1f;
    if (ValueAnimator.areAnimatorsEnabled())
      animate().scaleX(scale).scaleY(scale).setDuration(110).setInterpolator(easing).start();
    else {
      setScaleX(scale);
      setScaleY(scale);
    }
  }

  @Override
  public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    info.setClassName(Button.class.getName());
    info.setCanOpenPopup(true);
  }
}
