package com.webtoonmap.mobile.network;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.util.concurrent.ConcurrentHashMap;

public final class NetworkRetry {
    @FunctionalInterface
    public interface Request<T> {
        T execute() throws Exception;
    }

    private static final long RETRY_DELAY_MS = 3_000L;
    private static final ConcurrentHashMap<Thread, HttpURLConnection> ACTIVE =
            new ConcurrentHashMap<>();

    private NetworkRetry() { }

    public static <T> T forever(Request<T> request) throws Exception {
        while (true) {
            checkCancelled();
            try {
                return request.execute();
            } catch (IOException error) {
                checkCancelled();
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
    }

    public static <T extends HttpURLConnection> T track(T connection) {
        Thread thread = Thread.currentThread();
        ACTIVE.put(thread, connection);
        if (thread.isInterrupted() && ACTIVE.remove(thread, connection)) {
            connection.disconnect();
        }
        return connection;
    }

    public static void release(HttpURLConnection connection) {
        ACTIVE.remove(Thread.currentThread(), connection);
    }

    public static void cancel(Thread thread) {
        if (thread == null) return;
        thread.interrupt();
        HttpURLConnection connection = ACTIVE.remove(thread);
        if (connection != null) connection.disconnect();
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("다운로드 중단");
        }
    }
}



