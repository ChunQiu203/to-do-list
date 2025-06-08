package com.todoapp.shared;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.ObjectInputStream;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import javafx.application.Platform;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public class TaskNetworkService {
    private final String baseUrl;
    private final HttpClient client;
    private final Gson gson;
    private String userId;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> syncSchedule;
    private final AtomicBoolean isSyncing;
    private Consumer<TaskSyncData> onDataUpdated;
    private Consumer<Throwable> onError;
    private static final int RETRY_ATTEMPTS = 3;
    private static final long SYNC_INTERVAL = 30; // 同步间隔（秒）

    public TaskNetworkService(String baseUrl) {
        this.baseUrl = baseUrl;
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.isSyncing = new AtomicBoolean(false);

        gson = new GsonBuilder()
                .registerTypeAdapter(LocalDate.class, new LocalDateAdapter())
                .create();
    }

    public void setUserId(String userId) {
        this.userId = userId;
        startPeriodicSync();
    }

    public void setCallbacks(Consumer<TaskSyncData> onDataUpdated, Consumer<Throwable> onError) {
        this.onDataUpdated = onDataUpdated;
        this.onError = onError;
    }

    private void startPeriodicSync() {
        if (syncSchedule != null) {
            syncSchedule.cancel(false);
        }
        syncSchedule = scheduler.scheduleAtFixedRate(
            this::performSync,
            0, SYNC_INTERVAL, TimeUnit.SECONDS
        );
    }

    private void performSync() {// 同步
        if (!isSyncing.compareAndSet(false, true)) {
            return; // 避免重复同步
        }
        fetchTasks()
            .thenAccept(data -> {
                if (onDataUpdated != null) {
                    Platform.runLater(() -> onDataUpdated.accept(data));
                }
            })
            .exceptionally(error -> {
                handleError(error);
                return null;
            })
            .whenComplete((v, t) -> isSyncing.set(false));

    }

    public CompletableFuture<TaskSyncData> fetchTasks() {
        return retryOperation(() -> {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tasks/user/" + userId))
                .GET()
                .build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Server returned status: " + response.statusCode());
                    }
                    try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(response.body()))) {
                        return (TaskSyncData) ois.readObject();
                    } catch (Exception e) {
                        throw new RuntimeException("反序列化失败", e);
                    }
                });
        });
    }
    public CompletableFuture<Void> syncTasks(TaskSyncData data) {
        return retryOperation(() -> {
            // 使用 Java 原生序列化
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                oos.writeObject(data);
            } catch (IOException e) {
                throw new RuntimeException("序列化失败", e);
            }
            byte[] bytes = bos.toByteArray();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tasks/sync/" + userId))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build();
            // 修正异步响应处理
            return client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenApply(response -> {
                        if (response.statusCode() != 200) {
                            throw new RuntimeException("服务器返回状态码: " + response.statusCode());
                        }
                        return null; // 确保返回 Void 类型
                    });
        });
    }

    private <T> CompletableFuture<T> retryOperation(Supplier<CompletableFuture<T>> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        retryOperation(operation, RETRY_ATTEMPTS, future);
        return future;
    }

    private <T> void retryOperation(
            Supplier<CompletableFuture<T>> operation,
            int attemptsLeft,
            CompletableFuture<T> future) {
        operation.get()
            .thenAccept(future::complete)
            .exceptionally(error -> {
                if (attemptsLeft > 1) {
                    scheduler.schedule(
                        () -> retryOperation(operation, attemptsLeft - 1, future),
                        2, TimeUnit.SECONDS
                    );
                } else {
                    handleError(error);
                    future.completeExceptionally(error);
                }
                return null;
            });
    }

    private void handleError(Throwable error) {
        if (onError != null) {
            Platform.runLater(() -> onError.accept(error));
        }
    }

    public void shutdown() {
        if (syncSchedule != null) {
            syncSchedule.cancel(false);
        }
        scheduler.shutdown();
    }
    private static class LocalDateAdapter extends TypeAdapter<LocalDate> {
        @Override
        public void write(JsonWriter out, LocalDate value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }
        @Override
        public LocalDate read(JsonReader in) throws IOException {
            String dateStr = in.nextString();
            return dateStr == null? null : LocalDate.parse(dateStr);
        }
    }
}
