package com.sbro.emucorer.translate;

import android.graphics.Bitmap;
import android.content.Context;
import com.google.mlkit.common.sdkinternal.MlKitContext;
import java.util.function.Supplier;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

/** All public calls and task callbacks run on Android's main thread. No game data is uploaded. */
public final class DukeTranslator {
    public interface Result<T> { void done(T value, Exception error); }
    private final String source, target;
    private TextRecognizer recognizer;
    private final Supplier<TextRecognizer> recognizerFactory;
    private final Translator translator;
    private boolean retired, released;
    private int operations;
    public boolean ready;

    public DukeTranslator(Context context, String source, String target) {
        this(source, target, createTranslator(context, source, target), () -> createRecognizer(source));
    }

    // Downloading language models must not depend on creating an OCR client.
    DukeTranslator(String source, String target, Translator translator, Supplier<TextRecognizer> factory) {
        this.source = source;
        this.target = target;
        this.translator = translator;
        this.recognizerFactory = factory;
    }

    private static Translator createTranslator(Context context, String source, String target) {
        // Idempotent: also supports contexts where the startup provider did not run.
        MlKitContext.initializeIfNeeded(context.getApplicationContext());
        return source.equals(target) ? null : Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(source).setTargetLanguage(target).build());
    }

    private static TextRecognizer createRecognizer(String source) {
        switch (source) {
            case "ja": return TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
            case "zh": return TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            case "ko": return TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
            default: return TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        }
    }

    public void checkModels(Result<Boolean> result) {
        if (retired) return;
        if (translator == null) { ready = true; result.done(true, null); return; }
        operations++;
        try {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class)
                .addOnCompleteListener(task -> {
                    try {
                        boolean hasSource = source.equals("en"), hasTarget = target.equals("en");
                        if (task.isSuccessful()) {
                            for (TranslateRemoteModel model : task.getResult()) {
                                hasSource |= source.equals(model.getLanguage());
                                hasTarget |= target.equals(model.getLanguage());
                            }
                        }
                        ready = ready || (task.isSuccessful() && hasSource && hasTarget);
                        result.done(ready, task.getException());
                    } finally { finish(); }
                });
        } catch (RuntimeException | LinkageError failure) {
            Exception error = asException(failure);
            try { result.done(false, error); } finally { finish(); }
        }
    }

    // Model downloads are explicitly requested by the user and may use any connection.
    static DownloadConditions downloadConditions() {
        return new DownloadConditions.Builder().build();
    }

    public void downloadModels(Result<Boolean> result) {
        if (retired) return;
        if (translator == null) { ready = true; result.done(true, null); return; }
        operations++;
        try {
        translator.downloadModelIfNeeded(downloadConditions()).addOnCompleteListener(task -> {
            try { ready = task.isSuccessful(); result.done(ready, task.getException()); }
            finally { finish(); }
        });
        } catch (RuntimeException | LinkageError failure) {
            Exception error = asException(failure);
            try { result.done(false, error); } finally { finish(); }
        }
    }

    /** Takes ownership of bitmap; releases it after OCR, including failure paths. */
    public void recognize(Bitmap bitmap, Result<String> result) {
        if (retired) { bitmap.recycle(); result.done(null, new IllegalStateException("Closed")); return; }
        operations++;
        try {
            if (recognizer == null) recognizer = recognizerFactory.get();
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener(task -> {
                bitmap.recycle();
                try { result.done(task.isSuccessful() ? task.getResult().getText().trim() : null, task.getException()); }
                finally { finish(); }
            });
        } catch (RuntimeException | LinkageError failure) {
            bitmap.recycle();
            Exception error = asException(failure);
            try { result.done(null, error); } finally { finish(); }
        }
    }

    public void translate(String text, Result<String> result) {
        if (retired) { result.done(null, new IllegalStateException("Closed")); return; }
        if (translator == null) { result.done(text, null); return; }
        operations++;
        try {
            translator.translate(text).addOnCompleteListener(task -> {
                try { result.done(task.isSuccessful() ? task.getResult() : null, task.getException()); }
                finally { finish(); }
            });
        } catch (RuntimeException | LinkageError failure) {
            Exception error = asException(failure);
            try { result.done(null, error); } finally { finish(); }
        }
    }

    private static Exception asException(Throwable failure) {
        return failure instanceof Exception ? (Exception) failure : new IllegalStateException("ML Kit dependency could not load", failure);
    }

    public void close() { retired = true; releaseIfIdle(); }
    private void finish() { operations--; releaseIfIdle(); }
    private void releaseIfIdle() {
        if (retired && operations == 0 && !released) {
            released = true;
            try { if (recognizer != null) recognizer.close(); }
            finally { if (translator != null) translator.close(); }
        }
    }
}
