package com.victorlanes.camip.core;

/** Log simples que funciona no Android e no PC (testes). */
public final class CoreLog {
    public interface Sink {
        void log(String msg);
    }

    private static volatile Sink sink = System.out::println;

    private CoreLog() {
    }

    public static void setSink(Sink s) {
        sink = s;
    }

    public static void i(String msg) {
        try {
            sink.log(msg);
        } catch (Exception ignored) {
        }
    }
}
