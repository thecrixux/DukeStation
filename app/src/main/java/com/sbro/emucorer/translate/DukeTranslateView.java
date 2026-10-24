package com.sbro.emucorer.translate;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.*;
import android.util.LruCache;
import android.util.Log;
import com.google.mlkit.nl.translate.TranslateLanguage;
import java.util.*;

/** An in-app overlay. Captures ONLY the emulator SurfaceView, never the window or other apps. */
public final class DukeTranslateView extends FrameLayout {
    private static final int GOLD = 0xffffb800, INK = 0xff18181b, WHITE = 0xfff2f2f2;
    private static final String[] SOURCES = {"ja", "en", "es", "fr", "de", "it", "pt", "zh", "ko", "nl", "pl", "tr", "vi"};
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final boolean spanish;
    private final Button trigger;
    private final LinearLayout panel;
    private final TextView output;
    private final WindowScrollView textScroll;
    private final LinearLayout attribution;
    private final Space footerSpacer;
    private String startupError = "";
    private final LruCache<String, String> cache = new LruCache<>(64);
    private SurfaceView surface;
    private float[] drawRect;
    private DukeTranslator engine;
    private boolean enabled, automatic, original, busy, disposed, hostResumed, allowed, autoError;
    private boolean downloading;
    private String downloadStatus = "";
    private TextView downloadStatusView;
    private ProgressBar downloadProgress;
    private int downloadAttempt;
    private Runnable downloadTimeout;
    private int generation, region, interval;
    private String source, target, lastText = "";
    private AlertDialog settings;
    private float buttonX, buttonY;
    private float panelX = .5f, panelY, panelWidthDp, panelHeightDp;
    private int panelOpacity;
    private boolean fitPanelText, resizingPanel, commitWindowAfterLayout;
    private float resizeLeft, resizeTop;
    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!canCapture() || !automatic || autoError) return;
            if (!busy && ensureEngine() && engine.ready) capture(false);
            main.postDelayed(this, interval);
        }
    };

    public DukeTranslateView(Context context) {
        super(context);
        spanish = context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("es");
        prefs = context.getSharedPreferences("duke_translate", Context.MODE_PRIVATE);
        setClipChildren(false);
        setClickable(false);
        trigger = button("DukeTranslate");
        trigger.setContentDescription(tr("Traducir pantalla. Mantén pulsado para configurar.", "Translate screen. Long press for settings."));
        addView(trigger, new LayoutParams(dp(112), dp(60)));
        trigger.setOnClickListener(v -> { autoError = false; capture(true); restartLoop(); });
        trigger.setOnLongClickListener(v -> { showSettings(); return true; });
        enableDragging();
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(4), dp(10), dp(8));
        panel.setBackground(background(0xee18181b));
        panel.setClickable(true);
        LinearLayout bar = new LinearLayout(context);
        TextView title = label("↔ DukeTranslate", 13);
        title.setContentDescription(tr("Arrastra el título para mover la ventana", "Drag the title to move the window"));
        enableWindowGesture(title, false);
        title.setTextColor(GOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button config = button("⚙");
        config.setBackground(background(0x0018181b));
        config.setContentDescription(tr("Ajustes de traducción", "Translation settings"));
        config.setOnClickListener(v -> showSettings());
        bar.addView(config, new LinearLayout.LayoutParams(dp(48), dp(42)));
        Button close = button("×");
        close.setBackground(background(0x0018181b));
        close.setContentDescription(tr("Ocultar traducción", "Hide translation"));
        close.setOnClickListener(v -> panel.setVisibility(GONE));
        bar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(42)));
        panel.addView(bar);
        output = label("", 17);
        output.setTextIsSelectable(true);
        output.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        textScroll = new WindowScrollView(context);
        textScroll.addView(output);
        panel.addView(textScroll, new LinearLayout.LayoutParams(-1, -2));
        attribution = new LinearLayout(context);
        attribution.setGravity(Gravity.CENTER_VERTICAL);
        attribution.addView(label("powered by ", 12));
        ImageView google = new ImageView(context);
        google.setContentDescription("Google");
        google.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try (java.io.InputStream badge = context.getAssets().open("translate/googlelogo.png")) {
            google.setImageDrawable(android.graphics.drawable.Drawable.createFromStream(badge, null));
        } catch (java.io.IOException ignored) { google.setVisibility(GONE); attribution.addView(label("Google", 12)); }
        attribution.addView(google, new LinearLayout.LayoutParams(dp(62), dp(20)));
        attribution.addView(label(" Translate", 12));
        attribution.setVisibility(GONE);
        LinearLayout footer = new LinearLayout(context);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(attribution, new LinearLayout.LayoutParams(0, -2, 1));
        // A spacer keeps the handle at the right even when there is no attribution.
        footerSpacer = new Space(context);
        footer.addView(footerSpacer, new LinearLayout.LayoutParams(0, 1, 1));
        Button resize = button("↘");
        resize.setBackground(background(0x0018181b));
        resize.setContentDescription(tr("Arrastra para cambiar el tamaño de la ventana", "Drag to resize the window"));
        enableWindowGesture(resize, true);
        footer.addView(resize, new LinearLayout.LayoutParams(dp(40), dp(32)));
        panel.addView(footer);

        LayoutParams pp = new LayoutParams(dp(440), -2, Gravity.TOP | Gravity.LEFT);
        addView(panel, pp);
        panel.setVisibility(GONE);
        trigger.bringToFront();
        reload();
    }

    public void updateSource(SurfaceView value, boolean canRun, float[] gameRect) {
        if (disposed) return;
        boolean changed = surface != value || allowed != canRun;
        surface = value;
        allowed = canRun;
        drawRect = gameRect == null ? null : gameRect.clone();
        if (changed) { generation++; lastText = ""; panel.setVisibility(GONE); restartLoop(); }
        updateVisibility();
    }
    public void setHostResumed(boolean resumed) {
        if (disposed || hostResumed == resumed) return;
        hostResumed = resumed;
        generation++;
        panel.setVisibility(GONE);
        updateVisibility();
        restartLoop();
    }
    private boolean canCapture() {
        return !disposed && enabled && hostResumed && allowed && settings == null && surface != null && surface.isAttachedToWindow();
    }
    private void updateVisibility() { setVisibility(enabled && hostResumed && allowed ? VISIBLE : INVISIBLE); }
    private void restartLoop() {
        main.removeCallbacks(loop);
        if (canCapture() && automatic && !autoError) main.postDelayed(loop, interval);
    }
    private void reload() {
        generation++;
        if (engine != null) engine.close();
        engine = null;
        enabled = prefs.getBoolean("enabled", false);
        automatic = prefs.getBoolean("automatic", false);
        original = prefs.getBoolean("original", true);
        source = prefs.getString("source", "ja");
        if (!Arrays.asList(SOURCES).contains(source)) source = "ja";
        String locale = getResources().getConfiguration().getLocales().get(0).getLanguage();
        String defaultTarget = TranslateLanguage.fromLanguageTag(locale);
        target = prefs.getString("target", defaultTarget == null ? "en" : defaultTarget);
        if (!TranslateLanguage.getAllLanguages().contains(target)) target = "en";
        region = Math.max(0, Math.min(3, prefs.getInt("region", 0)));
        interval = Math.max(2000, Math.min(5000, prefs.getInt("interval", 3000)));
        buttonX = clamp(prefs.getFloat("button_x", .86f));
        buttonY = clamp(prefs.getFloat("button_y", .12f));
        loadWindowSettings();
        lastText = "";
        autoError = false;
        cache.evictAll();
        panel.setVisibility(GONE);
        updateVisibility();
        requestLayout();
        startupError = "";
        restartLoop();
    }
    private boolean ensureEngine() {
        if (engine != null) return true;
        if (disposed) return false;
        try {
            engine = new DukeTranslator(getContext(), source, target);
            startupError = "";
            DukeTranslator current = engine;
            current.checkModels((ready, error) -> {
                if (disposed || engine != current) return;
                if (error != null) {
                    Log.e("DukeTranslate", "Checking installed models failed", error);
                    if (!downloading) {
                        downloadStatus = tr("No se pudieron comprobar los modelos. Detalle: ", "Could not check installed models. Details: ") + downloadError(error);
                        updateDownloadUi();
                    }
                }
                if (!ready && canCapture())
                    message(error == null ? tr("Prepara los idiomas en ⚙ → Descargar modelos.", "Prepare languages in ⚙ → Download models.") : downloadStatus);
            });
            return true;
        } catch (RuntimeException | LinkageError error) {
            engine = null;
            autoError = true;
            Log.e("DukeTranslate", "Starting translation engine failed", error);
            startupError = tr("No se pudo iniciar el motor de traducción. Detalle: ", "Could not start the translation engine. Details: ") + downloadError(error);
            downloadStatus = startupError;
            updateDownloadUi();
            if (settings == null) message(startupError);
            return false;
        }
    }
    private void message(String text) {
        if (disposed) return;
        output.setText(text);
        attribution.setVisibility(GONE);
        footerSpacer.setVisibility(VISIBLE);
        panel.setVisibility(VISIBLE);
        requestLayout();
    }
    private void fail(String text) { busy = false; autoError = true; message(text); }

    private void capture(boolean manual) {
        if (!canCapture() || busy) return;
        if (!ensureEngine()) return;
        final DukeTranslator current = engine;
        if (!current.ready) {
            message(tr("Prepara los idiomas en ⚙ → Descargar modelos (Wi-Fi o datos móviles).", "Prepare languages in ⚙ → Download models (Wi-Fi or mobile data)."));
            return;
        }
        final SurfaceView capturedSurface = surface;
        int w = capturedSurface.getWidth(), h = capturedSurface.getHeight();
        if (w < 2 || h < 2 || !capturedSurface.getHolder().getSurface().isValid()) {
            if (manual) message(tr("La imagen del juego aún no está lista.", "The game image is not ready yet."));
            return;
        }
        Rect rect = captureRect(w, h);
        float scale = Math.min(2f, Math.min(1600f / rect.width(), 1200f / rect.height()));
        final Bitmap bitmap;
        try { bitmap = Bitmap.createBitmap(Math.max(1, Math.round(rect.width()*scale)), Math.max(1, Math.round(rect.height()*scale)), Bitmap.Config.ARGB_8888); }
        catch (RuntimeException | OutOfMemoryError error) { fail(tr("No hay memoria para capturar la imagen.", "Not enough memory to capture the image.")); return; }
        final int token = generation;
        busy = true;
        if (manual) message(tr("Leyendo texto…", "Reading text…"));
        try {
            PixelCopy.request(capturedSurface, rect, bitmap, status -> {
                if (disposed || token != generation || current != engine) { bitmap.recycle(); busy = false; return; }
                if (status != PixelCopy.SUCCESS) {
                    bitmap.recycle();
                    fail(tr("No se pudo capturar el juego. Pulsa el botón para reintentar.", "Could not capture the game. Tap the button to retry."));
                    return;
                }
                current.recognize(bitmap, (text, ocrError) -> {
                    if (disposed || token != generation || current != engine) { busy = false; return; }
                    if (ocrError != null) { Log.e("DukeTranslate", "OCR failed", ocrError); fail(tr("Error al leer el texto. Detalle: ", "Text recognition failed. Details: ") + downloadError(ocrError)); return; }
                    if (text == null || text.isEmpty()) {
                        busy = false; lastText = "";
                        if (manual) message(tr("No se detectó texto. Ajusta el idioma y la zona de lectura.", "No text detected. Adjust the language and capture region."));
                        else panel.setVisibility(GONE);
                        return;
                    }
                    if (text.length() > 3000) { fail(tr("Demasiado texto. Selecciona una zona más pequeña.", "Too much text. Choose a smaller capture region.")); return; }
                    if (!manual && text.equals(lastText)) { busy = false; return; }
                    String cached = cache.get(text);
                    if (cached != null) { busy = false; showResult(text, cached); return; }
                    message(tr("Traduciendo…", "Translating…"));
                    current.translate(text, (translated, error) -> {
                        busy = false;
                        if (disposed || token != generation || current != engine) return;
                        if (error != null || translated == null) { if (error != null) Log.e("DukeTranslate", "Translation failed", error); fail(tr("La traducción falló. Detalle: ", "Translation failed. Details: ") + downloadError(error)); return; }
                        cache.put(text, translated);
                        showResult(text, translated);
                    });
                });
            }, main);
        } catch (RuntimeException error) {
            bitmap.recycle();
            fail(tr("No se pudo capturar esta pantalla.", "Could not capture this screen."));
        }
    }
    private Rect captureRect(int w, int h) {
        int[] r = CaptureArea.calculate(w, h, drawRect, region);
        return new Rect(r[0], r[1], r[2], r[3]);
    }
    private void showResult(String text, String translated) {
        lastText = text;
        message(original ? translated + "\n\n" + text : translated);
        attribution.setVisibility(source.equals(target) ? GONE : VISIBLE);
        footerSpacer.setVisibility(source.equals(target) ? VISIBLE : GONE);
        textScroll.scrollTo(0, 0);
        requestLayout();
    }

    public void showSettings() {
        if (disposed || settings != null) return;
        generation++;
        main.removeCallbacks(loop);
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(12));
        content.setBackgroundColor(INK);
        downloadStatusView = label(downloadStatus, 14);
        downloadStatusView.setTextColor(GOLD);
        content.addView(downloadStatusView);
        downloadProgress = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        downloadProgress.setIndeterminate(true);
        downloadProgress.setIndeterminateTintList(ColorStateList.valueOf(GOLD));
        content.addView(downloadProgress);
        CheckBox enable = check(tr("Mostrar botón de traducción", "Show translation button"), enabled);
        CheckBox auto = check(tr("Traducción automática", "Automatic translation"), automatic);
        CheckBox showOriginal = check(tr("Mostrar también el texto original", "Also show original text"), original);
        content.addView(enable);
        content.addView(auto);
        content.addView(showOriginal);
        content.addView(label(tr("Ventana de traducción", "Translation window"), 18));
        content.addView(label(tr("Arrastra el título para moverla y la esquina ↘ para redimensionarla. Los textos largos se pueden desplazar dentro de la ventana.", "Drag the title to move it and the ↘ corner to resize it. Long text can be scrolled inside the window."), 13));
        CheckBox fitText = check(tr("Ajustar altura al texto", "Fit height to text"), fitPanelText);
        content.addView(fitText);
        int maxWidth = Math.max(240, Math.min(800, Math.round((getWidth() - dp(16)) / density())));
        int maxHeight = Math.max(120, Math.min(800, Math.round((getHeight() - dp(16)) / density())));
        SeekBar windowWidth = windowSlider(content, tr("Anchura", "Width"), 240, maxWidth, Math.round(panelWidthDp), "%screen");
        SeekBar windowHeight = windowSlider(content, tr("Altura máxima", "Maximum height"), 120, maxHeight, Math.round(panelHeightDp), "%screen");
        SeekBar opacity = windowSlider(content, tr("Opacidad del fondo", "Background opacity"), 20, 100, panelOpacity, "%");
        Runnable previewWindow = () -> {
            fitPanelText = fitText.isChecked();
            ((Object[])windowHeight.getTag())[1] = fitPanelText ? tr("Altura máxima", "Maximum height") : tr("Altura", "Height");
            updateSliderCaption(windowHeight);
            panelWidthDp = 240 + windowWidth.getProgress();
            panelHeightDp = 120 + windowHeight.getProgress();
            panelOpacity = 20 + opacity.getProgress();
            applyWindowAppearance();
        };
        previewWindow.run();
        fitText.setOnCheckedChangeListener((v, checked) -> previewWindow.run());
        addWindowPreview(windowWidth, previewWindow);
        addWindowPreview(windowHeight, previewWindow);
        addWindowPreview(opacity, previewWindow);
        Button resetWindow = button(tr("Restablecer ventana", "Reset window"));
        resetWindow.setOnClickListener(v -> {
            panelX = .5f; panelY = 0;
            fitText.setChecked(true);
            windowWidth.setProgress(TranslationWindowGeometry.DEFAULT_WIDTH - 240);
            windowHeight.setProgress(TranslationWindowGeometry.DEFAULT_HEIGHT - 120);
            opacity.setProgress(TranslationWindowGeometry.DEFAULT_OPACITY - 20);
            previewWindow.run();
        });
        content.addView(resetWindow);
        List<String> targets = new ArrayList<>(TranslateLanguage.getAllLanguages());
        targets.sort(Comparator.comparing(this::languageName));
        Spinner from = languages(content, tr("Idioma del juego", "Game language"), Arrays.asList(SOURCES), source);
        Spinner to = languages(content, tr("Traducir a", "Translate into"), targets, target);
        content.addView(label(tr("Zona de lectura", "Capture region"), 14));
        Spinner crop = spinner(new String[]{tr("Juego completo", "Full game"), tr("Mitad inferior", "Bottom half"), tr("Tercio inferior", "Bottom third"), tr("Mitad superior", "Top half")}, region);
        content.addView(crop);
        content.addView(label(tr("Intervalo automático", "Automatic interval"), 14));
        Spinner pace = spinner(new String[]{"2 s", "3 s", "5 s"}, interval == 2000 ? 0 : interval == 5000 ? 2 : 1);
        content.addView(pace);
        content.addView(label(tr("Descargas por Wi-Fi o datos móviles. Solo se inician al pulsar Descargar modelos.", "Downloads use Wi-Fi or mobile data. They only start when you tap Download models."), 13));
        TextView status = label(tr("OCR y traducción en el dispositivo. Los modelos necesitan una descarga inicial (decenas de MB por idioma). La lectura de fuentes pequeñas o decoradas puede fallar.\nArrastra el botón para moverlo; mantén pulsado para configurar.\nInspirado en PlayTranslate. Motor: Google ML Kit.", "On-device OCR and translation. Models need an initial download (tens of MB per language). Small or decorative fonts may not be read correctly.\nDrag the button to move it; long press for settings.\nInspired by PlayTranslate. Engine: Google ML Kit."), 13);
        content.addView(status);
        Button notices = button(tr("Información y créditos", "Information and credits"));
        notices.setOnClickListener(v -> new AlertDialog.Builder(getContext())
                .setTitle("DukeTranslate")
                .setMessage(tr("Traducción automática mediante Google Translate (ML Kit en el dispositivo). Google no garantiza la precisión, fiabilidad, comerciabilidad, idoneidad para un fin concreto ni ausencia de infracciones de las traducciones.\n\nhttps://cloud.google.com/translate\nhttps://developers.google.com/ml-kit\n\nIdea de traducción en pantalla inspirada en PlayTranslate, de dominostars. Esta integración es una implementación propia de DukeStation.\nhttps://github.com/dominostars/playtranslate",
                        "Machine translation powered by Google Translate (on-device ML Kit). Google provides no warranties, express or implied, for accuracy, reliability, merchantability, fitness for a particular purpose or non-infringement.\n\nhttps://cloud.google.com/translate\nhttps://developers.google.com/ml-kit\n\nScreen translation inspired by PlayTranslate, by dominostars. This integration is DukeStation's own implementation.\nhttps://github.com/dominostars/playtranslate"))
                .setPositiveButton("OK", null).show());
        content.addView(notices);
        ScrollView scroll = new ScrollView(getContext());
        scroll.addView(content);
        Runnable save = () -> {
            previewWindow.run();
            saveWindowSettings();
            prefs.edit().putBoolean("enabled", enable.isChecked()).putBoolean("automatic", auto.isChecked())
                    .putBoolean("original", showOriginal.isChecked()).putString("source", SOURCES[from.getSelectedItemPosition()])
                    .putString("target", targets.get(to.getSelectedItemPosition())).putInt("region", crop.getSelectedItemPosition())
                    .putInt("interval", new int[]{2000,3000,5000}[pace.getSelectedItemPosition()]).apply();
            reload();
        };
        TextView dialogTitle = label("DukeTranslate", 22);
        dialogTitle.setTextColor(GOLD);
        dialogTitle.setPadding(dp(20), dp(16), dp(20), dp(8));
        settings = new AlertDialog.Builder(getContext()).setCustomTitle(dialogTitle).setView(scroll)
                .setPositiveButton(tr("Guardar", "Save"), (d, which) -> save.run())
                .setNegativeButton(tr("Cancelar", "Cancel"), null)
                .setNeutralButton(tr("Descargar modelos", "Download models"), null).create();
        settings.setOnDismissListener(d -> {
            settings = null; downloadStatusView = null; downloadProgress = null; loadWindowSettings(); restartLoop();
        });
        settings.show();
        settings.getWindow().setBackgroundDrawable(background(INK));
        final AlertDialog dialog = settings;
        updateDownloadUi();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            if (downloading) return;
            // Download is explicit; settings are committed so the chosen pair matches the installed models.
            enable.setChecked(true);
            save.run();
            if (!ensureEngine()) {
                scroll.post(() -> scroll.smoothScrollTo(0, 0));
                return;
            }
            downloading = true;
            final int attempt = ++downloadAttempt;
            final String pair = languageName(source) + " → " + languageName(target);
            DukeTranslator current = engine;
            downloadStatus = pair + "\n" + tr("Preparando modelos con tu conexión actual… Puedes guardar o cerrar el diálogo; la descarga continúa.", "Preparing models using your current connection… You may save or close this dialog; the download continues.");
            updateDownloadUi();
            scroll.post(() -> scroll.smoothScrollTo(0, 0));
            if (downloadTimeout != null) main.removeCallbacks(downloadTimeout);
            downloadTimeout = () -> {
                if (disposed || attempt != downloadAttempt || !downloading) return;
                downloading = false;
                downloadAttempt++;
                downloadStatus = pair + "\n" + tr("La descarga aún no ha terminado después de 3 minutos. Comprueba la conexión y vuelve a pulsar Descargar modelos. Android puede seguir descargando en segundo plano.", "Download has not finished after 3 minutes. Check your connection and tap Download models again. Android may still be downloading in the background.");
                updateDownloadUi();
            };
            main.postDelayed(downloadTimeout, 180000L);
            current.downloadModels((ready, error) -> {
                if (disposed) return;
                // Saving or changing languages may replace the engine during the download.
                if (engine != null && engine != current) engine.checkModels((ok, failure) -> {});
                if (attempt != downloadAttempt) return;
                downloading = false;
                if (downloadTimeout != null) main.removeCallbacks(downloadTimeout);
                downloadTimeout = null;
                downloadStatus = pair + "\n" + (ready
                        ? tr("Modelos preparados. Guarda y vuelve al juego.", "Models ready. Save and return to the game.")
                        : tr("No se pudieron descargar los modelos. Reintenta. Detalle: ", "Could not download models. Retry. Details: ") + downloadError(error));
                // Refresh whichever dialog is visible now, not only the one that started the task.
                updateDownloadUi();
            });
        });
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL})
            dialog.getButton(which).setTextColor(GOLD);
    }

    private void updateDownloadUi() {
        if (disposed) return;
        if (downloadStatusView != null) {
            downloadStatusView.setText(downloadStatus);
            downloadStatusView.setVisibility(downloadStatus.isEmpty() ? GONE : VISIBLE);
        }
        if (downloadProgress != null) downloadProgress.setVisibility(downloading ? VISIBLE : GONE);
        if (settings != null && settings.isShowing()) {
            settings.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(!downloading);
        }
    }
    private String downloadError(Throwable error) {
        if (error == null) return tr("Sin detalles del servicio.", "No service details available.");
        StringBuilder result = new StringBuilder(error.getClass().getSimpleName());
        if (error instanceof com.google.mlkit.common.MlKitException)
            result.append(" [").append(((com.google.mlkit.common.MlKitException) error).getErrorCode()).append("]");
        Throwable cause = error;
        for (int i = 0; i < 3 && cause != null; i++, cause = cause.getCause()) {
            if (cause.getMessage() != null) result.append(": ").append(cause.getMessage());
        }
        return result.substring(0, Math.min(result.length(), 600));
    }

    private Spinner languages(LinearLayout root, String title, List<String> codes, String selected) {
        root.addView(label(title, 14));
        String[] names = new String[codes.size()];
        for (int i = 0; i < names.length; i++) names[i] = languageName(codes.get(i));
        Spinner view = spinner(names, Math.max(0, codes.indexOf(selected)));
        root.addView(view);
        return view;
    }
    private String languageName(String code) { return new Locale(code).getDisplayLanguage(new Locale(spanish ? "es" : "en")); }
    private Spinner spinner(String[] items, int selected) {
        Spinner view = new Spinner(getContext());
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(getContext(), android.R.layout.simple_spinner_item, items) {
            @Override public View getView(int pos, View old, android.view.ViewGroup parent) {
                TextView v = (TextView) super.getView(pos, old, parent); v.setTextColor(GOLD); v.setPadding(dp(8),dp(10),dp(8),dp(10)); return v;
            }
            @Override public View getDropDownView(int pos, View old, android.view.ViewGroup parent) {
                TextView v = (TextView) super.getDropDownView(pos, old, parent); v.setTextColor(WHITE); v.setBackgroundColor(INK); return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        view.setAdapter(adapter); view.setSelection(selected);
        return view;
    }
    private CheckBox check(String text, boolean checked) {
        CheckBox box = new CheckBox(getContext()); box.setText(text); box.setTextColor(WHITE); box.setChecked(checked);
        box.setButtonTintList(ColorStateList.valueOf(GOLD)); return box;
    }
    private TextView label(String text, int size) {
        TextView v = new TextView(getContext()); v.setText(text); v.setTextColor(WHITE); v.setTextSize(size); v.setPadding(0,dp(5),0,dp(5)); return v;
    }
    private Button button(String text) {
        Button b = new Button(getContext()); b.setText(text); b.setTextColor(GOLD); b.setTextSize(14); b.setAllCaps(false);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(0,0,0,0); b.setBackground(background(0xee18181b)); return b;
    }
    private GradientDrawable background(int color) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(14)); d.setStroke(dp(1), 0x66ffb800); return d;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String tr(String es, String en) { return spanish ? es : en; }
    private static float clamp(float n) { return Float.isFinite(n) ? Math.max(0, Math.min(1, n)) : .5f; }
    private void enableDragging() {
        trigger.setOnTouchListener(new OnTouchListener() {
            float downX, downY, startX, startY; boolean dragging;
            final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX(); downY = event.getRawY(); startX = v.getX(); startY = v.getY(); dragging = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX()-downX, dy = event.getRawY()-downY;
                        if (!dragging && Math.hypot(dx,dy)>slop) { dragging = true; v.cancelLongPress(); v.setPressed(false); }
                        if (dragging) {
                            v.setX(Math.max(dp(8), Math.min(getWidth()-v.getWidth()-dp(8),startX+dx)));
                            v.setY(Math.max(dp(8), Math.min(getHeight()-v.getHeight()-dp(8),startY+dy)));
                            buttonX = clamp(v.getX()/Math.max(1f,getWidth()-v.getWidth()));
                            buttonY = clamp(v.getY()/Math.max(1f,getHeight()-v.getHeight()));
                            return true;
                        }
                        return false;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            buttonX = clamp(v.getX()/Math.max(1f,getWidth()-v.getWidth()));
                            buttonY = clamp(v.getY()/Math.max(1f,getHeight()-v.getHeight()));
                            prefs.edit().putFloat("button_x",buttonX).putFloat("button_y",buttonY).apply();
                            v.setPressed(false); return true;
                        }
                        return false;
                    default: return false;
                }
            }
        });
    }
    private void loadWindowSettings() {
        panelX = TranslationWindowGeometry.normalized(prefs.getFloat("window_x", .5f));
        panelY = TranslationWindowGeometry.normalized(prefs.getFloat("window_y", 0));
        panelWidthDp = prefs.getFloat("window_width", TranslationWindowGeometry.DEFAULT_WIDTH);
        panelHeightDp = prefs.getFloat("window_height", TranslationWindowGeometry.DEFAULT_HEIGHT);
        if (!Float.isFinite(panelWidthDp)) panelWidthDp = TranslationWindowGeometry.DEFAULT_WIDTH;
        if (!Float.isFinite(panelHeightDp)) panelHeightDp = TranslationWindowGeometry.DEFAULT_HEIGHT;
        panelWidthDp = Math.max(240, Math.min(800, panelWidthDp));
        panelHeightDp = Math.max(120, Math.min(800, panelHeightDp));
        panelOpacity = TranslationWindowGeometry.opacity(prefs.getInt("window_opacity", TranslationWindowGeometry.DEFAULT_OPACITY));
        fitPanelText = prefs.getBoolean("window_fit_text", true);
        applyWindowAppearance();
    }
    private void saveWindowSettings() {
        if (disposed) return;
        prefs.edit().putFloat("window_x", panelX).putFloat("window_y", panelY)
                .putFloat("window_width", panelWidthDp).putFloat("window_height", panelHeightDp)
                .putInt("window_opacity", panelOpacity).putBoolean("window_fit_text", fitPanelText).apply();
    }
    private void applyWindowAppearance() {
        int alpha = Math.round(255 * panelOpacity / 100f);
        panel.setBackground(background((alpha << 24) | (INK & 0x00ffffff)));
        requestLayout();
    }
    private float density() { return getResources().getDisplayMetrics().density; }

    // Wrap short text naturally; limit long text to the selected height, with scrolling.
    private static final class WindowScrollView extends ScrollView {
        int limit = Integer.MAX_VALUE;
        WindowScrollView(Context context) { super(context); setFillViewport(false); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int mode = MeasureSpec.getMode(heightSpec);
            if (mode != MeasureSpec.EXACTLY) {
                int available = mode == MeasureSpec.UNSPECIFIED ? limit : Math.min(limit, MeasureSpec.getSize(heightSpec));
                heightSpec = MeasureSpec.makeMeasureSpec(Math.max(1, available), MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        LayoutParams p = (LayoutParams) panel.getLayoutParams();
        int newWidth = TranslationWindowGeometry.width(w, density(), panelWidthDp);
        int maxHeight = TranslationWindowGeometry.height(h, density(), panelHeightDp);
        int newHeight = fitPanelText ? LayoutParams.WRAP_CONTENT : maxHeight;
        int newLimit = Math.max(1, maxHeight - dp(86));
        LinearLayout.LayoutParams textParams = (LinearLayout.LayoutParams) textScroll.getLayoutParams();
        int newTextHeight = fitPanelText ? LayoutParams.WRAP_CONTENT : 0;
        float newWeight = fitPanelText ? 0 : 1;
        if (p.width != newWidth || p.height != newHeight || textScroll.limit != newLimit
                || textParams.height != newTextHeight || textParams.weight != newWeight) {
            // View.measure caches identical specs: changing just the cap must invalidate that cache.
            panel.forceLayout(); textScroll.forceLayout();
        }
        p.width = newWidth; p.height = newHeight;
        textScroll.limit = newLimit;
        textParams.height = newTextHeight; textParams.weight = newWeight;
        // Updating existing LayoutParams avoids recursively requesting layout during measure.
        super.onMeasure(widthSpec, heightSpec);
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed,l,t,r,b);
        trigger.setX(Math.max(dp(8), Math.min(getWidth()-trigger.getWidth()-dp(8), buttonX*(getWidth()-trigger.getWidth()))));
        trigger.setY(Math.max(dp(8), Math.min(getHeight()-trigger.getHeight()-dp(8), buttonY*(getHeight()-trigger.getHeight()))));
        if (resizingPanel) {
            panelX = TranslationWindowGeometry.fraction(getWidth(), panel.getWidth(), density(), resizeLeft);
            panelY = TranslationWindowGeometry.fraction(getHeight(), panel.getHeight(), density(), resizeTop);
        }
        panel.setX(TranslationWindowGeometry.position(getWidth(), panel.getWidth(), density(), panelX));
        panel.setY(TranslationWindowGeometry.position(getHeight(), panel.getHeight(), density(), panelY));
        if (resizingPanel && commitWindowAfterLayout) {
            resizingPanel = false; commitWindowAfterLayout = false;
            saveWindowSettings();
        }
    }
    private void enableWindowGesture(View control, boolean resize) {
        control.setOnTouchListener(new OnTouchListener() {
            float downX, downY, startX, startY, startW, startH;
            boolean active, moved;
            final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            private void finish() {
                if (!active) return;
                active = false;
                // Pending resize measurements still use the original top-left until laid out.
                if (resize && moved) { commitWindowAfterLayout = true; requestLayout(); }
                else if (moved) saveWindowSettings();
                control.setPressed(false);
            }
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        active = true; moved = false;
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = panel.getX(); startY = panel.getY();
                        startW = panel.getWidth(); startH = panel.getHeight();
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!active) return true;
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!moved && Math.hypot(dx, dy) <= slop) return true;
                        moved = true;
                        if (resize) {
                            resizeLeft = startX; resizeTop = startY; resizingPanel = true;
                            panelWidthDp = TranslationWindowGeometry.width(getWidth(), density(), (startW + dx) / density()) / density();
                            panelHeightDp = TranslationWindowGeometry.height(getHeight(), density(), (startH + dy) / density()) / density();
                            // A direct resize chooses an exact size; Fit height to text can be restored in settings.
                            fitPanelText = false;
                            requestLayout();
                        } else {
                            panelX = TranslationWindowGeometry.fraction(getWidth(), panel.getWidth(), density(), startX + dx);
                            panelY = TranslationWindowGeometry.fraction(getHeight(), panel.getHeight(), density(), startY + dy);
                            panel.setX(TranslationWindowGeometry.position(getWidth(), panel.getWidth(), density(), panelX));
                            panel.setY(TranslationWindowGeometry.position(getHeight(), panel.getHeight(), density(), panelY));
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (active && !moved) v.performClick();
                        finish(); return true;
                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_POINTER_DOWN:
                        finish(); return true;
                    default: return true;
                }
            }
        });
    }
    private SeekBar windowSlider(LinearLayout root, String name, int minimum, int maximum, int value, String unit) {
        TextView caption = label("", 14);
        root.addView(caption);
        SeekBar slider = new SeekBar(getContext());
        slider.setMax(maximum - minimum);
        slider.setProgress(Math.max(0, Math.min(maximum - minimum, value - minimum)));
        slider.setProgressTintList(ColorStateList.valueOf(GOLD));
        slider.setThumbTintList(ColorStateList.valueOf(GOLD));
        slider.setTag(new Object[]{caption, name, minimum, unit, maximum});
        updateSliderCaption(slider);
        root.addView(slider, new LinearLayout.LayoutParams(-1, dp(44)));
        return slider;
    }
    private void updateSliderCaption(SeekBar slider) {
        Object[] data = (Object[]) slider.getTag();
        int value = (int)data[2] + slider.getProgress();
        boolean screenPercent = "%screen".equals(data[3]);
        if (screenPercent) value = Math.round(100f * value / (int)data[4]);
        String caption = data[1] + ": " + value + " " + (screenPercent ? "%" : data[3]);
        ((TextView)data[0]).setText(caption);
        slider.setContentDescription(caption);
    }
    private void addWindowPreview(SeekBar slider, Runnable preview) {
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar v, int progress, boolean user) { updateSliderCaption(v); preview.run(); }
            @Override public void onStartTrackingTouch(SeekBar v) {}
            @Override public void onStopTrackingTouch(SeekBar v) {}
        });
    }
    public void dispose() {
        if (disposed) return;
        disposed = true; generation++; downloadAttempt++;
        if (downloadTimeout != null) main.removeCallbacks(downloadTimeout);
        downloadTimeout = null;
        main.removeCallbacks(loop);
        if (settings != null) settings.dismiss();
        if (engine != null) engine.close();
        engine = null; surface = null; cache.evictAll();
    }
}
