package com.sbro.emucorer.translate;

import com.google.mlkit.nl.translate.Translator;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class DukeTranslatorTest {
    @Test public void modelDownloadIsAttemptedEvenIfOcrCannotStart() {
        AtomicInteger downloads = new AtomicInteger(), closes = new AtomicInteger();
        RuntimeException networkFailure = new IllegalStateException("model service unavailable");
        Translator client = (Translator) Proxy.newProxyInstance(Translator.class.getClassLoader(),
                new Class<?>[]{Translator.class}, (proxy, method, args) -> {
                    if (method.getName().equals("downloadModelIfNeeded")) {
                        downloads.incrementAndGet();
                        assertFalse(((com.google.mlkit.common.model.DownloadConditions)args[0]).isWifiRequired());
                        throw networkFailure;
                    }
                    if (method.getName().equals("close")) { closes.incrementAndGet(); return null; }
                    throw new AssertionError(method.getName());
                });
        DukeTranslator engine = new DukeTranslator("ja", "en", client,
                () -> { throw new AssertionError("Downloading must not initialize OCR"); });
        AtomicInteger callbacks = new AtomicInteger();
        engine.downloadModels((ready, error) -> {
            callbacks.incrementAndGet(); assertFalse(ready); assertSame(networkFailure, error);
        });
        assertEquals(1, downloads.get());
        assertEquals(1, callbacks.get());
        engine.close(); engine.close();
        assertEquals(1, closes.get());
    }

    @Test public void missingSdkClassReportsAnErrorInsteadOfCrashingDownload() {
        Translator client = (Translator) Proxy.newProxyInstance(Translator.class.getClassLoader(),
                new Class<?>[]{Translator.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) return null;
                    throw new NoClassDefFoundError("missing ML Kit component");
                });
        DukeTranslator engine = new DukeTranslator("ja", "es", client, () -> null);
        AtomicInteger callbacks = new AtomicInteger();
        engine.downloadModels((ready, error) -> {
            callbacks.incrementAndGet(); assertFalse(ready);
            assertTrue(error.getCause() instanceof NoClassDefFoundError);
        });
        assertEquals(1, callbacks.get()); engine.close();
    }

    @Test public void identicalLanguagesNeedNoModelOrOcr() {
        DukeTranslator engine = new DukeTranslator("ja", "ja", null,
                () -> { throw new AssertionError("OCR not required for models"); });
        AtomicInteger callbacks = new AtomicInteger();
        engine.downloadModels((ready, error) -> { assertTrue(ready); assertNull(error); callbacks.incrementAndGet(); });
        assertEquals(1, callbacks.get()); assertTrue(engine.ready); engine.close();
    }
}
