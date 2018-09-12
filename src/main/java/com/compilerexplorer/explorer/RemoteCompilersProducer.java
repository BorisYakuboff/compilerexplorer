package com.compilerexplorer.explorer;

import com.compilerexplorer.common.RefreshSignal;
import com.compilerexplorer.common.SettingsProvider;
import com.compilerexplorer.common.TaskRunner;
import com.compilerexplorer.common.datamodel.state.RemoteCompilerInfo;
import com.compilerexplorer.common.datamodel.state.SettingsState;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class RemoteCompilersProducer<T> implements Consumer<T> {
    @NotNull
    private final Project project;
    @NotNull
    private final Consumer<T> consumer;
    @NotNull
    private final Consumer<Error> errorConsumer;
    @NotNull
    private final TaskRunner taskRunner;
    @Nullable
    private T lastT;

    public RemoteCompilersProducer(@NotNull Project project_,
                                   @NotNull Consumer<T> consumer_,
                                   @NotNull Consumer<Error> errorConsumer_,
                                   @NotNull TaskRunner taskRunner_) {
        project = project_;
        consumer = consumer_;
        errorConsumer = errorConsumer_;
        taskRunner = taskRunner_;
    }

    @Override
    public void accept(@NotNull T t) {
        lastT = t;
        SettingsState state = SettingsProvider.getInstance(project).getState();

        if (!state.getEnabled()) {
            return;
        }

        if (state.getConnected()) {
            System.out.println("RemoteCompilersProducer::accept already connected");
            consumer.accept(t);
            return;
        }

        System.out.println("RemoteCompilersProducer::accept starting task");
        String url = state.getUrl();
        taskRunner.runTask(new Task.Backgroundable(project, "Compiler Explorer: connecting to " + url) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                String endpoint = url + "/api/compilers";
                try {
                    CloseableHttpClient httpClient = HttpClients.createDefault();
                    HttpGet getRequest = new HttpGet(endpoint);
                    getRequest.addHeader("accept", "application/json");
                    HttpResponse response = httpClient.execute(getRequest);
                    if (response.getStatusLine().getStatusCode() != 200) {
                        httpClient.close();
                        throw new RuntimeException("Failed : HTTP error code : " + response.getStatusLine().getStatusCode() + " from " + url);
                    }
                    BufferedReader br = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
                    String output = "";
                    String line;
                    while ((line = br.readLine()) != null) {
                        indicator.checkCanceled();
                        output = output.concat(line);
                    }
                    httpClient.close();
                    indicator.checkCanceled();

                    JsonArray array = new JsonParser().parse(output).getAsJsonArray();
                    Gson gson = new Gson();
                    List<RemoteCompilerInfo> compilers = new ArrayList<>();
                    for (JsonElement elem : array) {
                        compilers.add(gson.fromJson(elem, RemoteCompilerInfo.class));
                    }
                    indicator.checkCanceled();
                    ApplicationManager.getApplication().invokeLater(() -> {
                        System.out.println("RemoteCompilersProducer::accept task finished");
                        state.setRemoteCompilers(compilers);
                        state.setConnected(true);
                        consumer.accept(t);
                    });
                } catch (ProcessCanceledException canceledException) {
                    errorLater("Canceled reading from " + url);
                } catch (Exception e) {
                    errorLater("Exception reading from " + url + ": " + e.getMessage());
                }
            }
        });
    }

    @NotNull
    public Consumer<RefreshSignal> asRefreshSignalConsumer() {
        return refreshSignal -> {
            System.out.println("RemoteCompilersProducer::asRefreshSignalConsumer");
            SettingsState state = SettingsProvider.getInstance(project).getState();
            state.setConnected(SettingsState.EMPTY.getConnected());
            state.setRemoteCompilers(SettingsState.EMPTY.getRemoteCompilers());
        };
    }

    private void errorLater(@NotNull String text) {
        System.out.println("RemoteCompilersProducer::errorLater");
        ApplicationManager.getApplication().invokeLater(() -> errorConsumer.accept(new Error(text)));
    }

    public void refresh() {
        if (lastT != null && SettingsProvider.getInstance(project).getState().getEnabled()) {
            accept(lastT);
        }
    }
}