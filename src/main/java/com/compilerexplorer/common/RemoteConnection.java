package com.compilerexplorer.common;

import com.google.gson.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator;
import com.intellij.openapi.project.Project;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.http.HttpResponse;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.client.methods.HttpGet;

public class RemoteConnection {
    static void connect(@NotNull Project project, @NotNull SettingsState state) {
        connect(project, state, true);
    }

    public static void tryConnect(@NotNull Project project, @NotNull SettingsState state) {
        connect(project, state, false);
    }

    private static class CompilerId {
        String id;
        String name;
        String lang;
    }

    private static void connect(@NotNull Project project, @NotNull SettingsState state, boolean publish) {
        SettingsState tmpState = new SettingsState();
        tmpState.copyFrom(state);
        Task.Backgroundable task = new Task.Backgroundable(project, "Compiler Explorer: connecting to " + state.getUrl()) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                tmpState.setConnected(false);
                tmpState.setLastConnectionStatus("");
                String url = tmpState.getUrl() + "/api/compilers";
                try {
                    CloseableHttpClient httpClient = HttpClients.createDefault();
                    HttpGet getRequest = new HttpGet(url);
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
                    List<SettingsState.RemoteCompilerInfo> compilers = new ArrayList<>();
                    for (JsonElement elem : array) {
                        CompilerId compilerId = gson.fromJson(elem, CompilerId.class);
                        SettingsState.RemoteCompilerInfo info = new SettingsState.RemoteCompilerInfo();
                        info.setId(compilerId.id);
                        info.setName(compilerId.name);
                        info.setLanguage(compilerId.lang);
                        compilers.add(info);
                    }
                    tmpState.setRemoteCompilers(compilers);
                    tmpState.setConnected(true);
                } catch (ProcessCanceledException canceledException) {
                    // empty
                } catch (Exception e) {
                    tmpState.setLastConnectionStatus("Exception reading from " + url + ": " + e.getMessage());
                }
                indicator.checkCanceled();
                ApplicationManager.getApplication().invokeLater(() -> {
                    state.copyFrom(tmpState);
                    if (publish) {
                        publishConnection(project);
                    }
                });
            }
        };
        ProgressManager.getInstance().runProcessWithProgressAsynchronously(task, new BackgroundableProcessIndicator(task));
    }

    static void publishConnectionLater(@NotNull Project project) {
        ApplicationManager.getApplication().invokeLater(() -> publishConnection(project));
    }

    private static void publishConnection(@NotNull Project project) {
        project.getMessageBus().syncPublisher(RemoteConnectionConsumer.TOPIC).connected();
    }

    private static class Options {
        String userArguments;
        SettingsState.Filters filters;
    }

    private static class Request {
        String source;
        Options options;
    }

    private static class SourceLocation {
        String file;
        int line;
    }

    private static class CompiledChunk {
        String text;
        SourceLocation source;
    }

    private static class CompiledResult {
        int code;
        List<CompiledChunk> stdout;
        List<CompiledChunk> stderr;
        List<CompiledChunk> asm;
    }

    public static void compile(@NotNull Project project, @NotNull SettingsState state, @NotNull PreprocessedSource preprocessedSource, @NotNull String userArguments, @NotNull CompiledTextConsumer compiledTextConsumer) {
        String name = preprocessedSource.getPreprocessableSource().getSourceRemoteMatched().getSourceCompilerSettings().getSourceSettings().getSource().getName();
        compiledTextConsumer.clearCompiledText("Compiling " + name + " ...");
        Task.Backgroundable task = new Task.Backgroundable(project, "Compiler Explorer: compiling " + name) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                String remoteCompilerId = preprocessedSource.getPreprocessableSource().getSourceRemoteMatched().getRemoteCompilerIds().get(0);
                String url = state.getUrl() + "/api/compiler/" + remoteCompilerId + "/compile";
                try {
                    CloseableHttpClient httpClient = HttpClients.createDefault();

                    HttpPost postRequest = new HttpPost(url);
                    postRequest.addHeader("accept", "application/json");

                    Gson gson = new Gson();

                    String source = /*preprocessedSource.getPreprocessableSource().getDefines() + */preprocessedSource.getPreprocessedText();
                    Request request = new Request();
                    request.source = source;
                    request.options = new Options();
                    request.options.userArguments = userArguments;
                    request.options.filters = state.getFilters();

                    postRequest.setEntity(new StringEntity(gson.toJson(request), ContentType.APPLICATION_JSON));

                    HttpResponse response = httpClient.execute(postRequest);
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

                    JsonObject obj = new JsonParser().parse(output).getAsJsonObject();
                    CompiledResult compiledResult = gson.fromJson(obj, CompiledResult.class);

                    String asm = compiledResult.asm.stream().map(c -> c.text).filter(Objects::nonNull).collect(Collectors.joining("\n"));
                    String err = compiledResult.stderr.stream().map(c -> c.text).filter(Objects::nonNull).collect(Collectors.joining("\n"));

                    if (compiledResult.code == 0) {
                        ApplicationManager.getApplication().invokeLater(() -> compiledTextConsumer.setCompiledText(new CompiledText(preprocessedSource, remoteCompilerId, asm)));
                    } else {
                        ApplicationManager.getApplication().invokeLater(() -> compiledTextConsumer.clearCompiledText(err + "\n" + source));
                    }
                } catch (ProcessCanceledException canceledException) {
                    // empty
                } catch (Exception e) {
                    ApplicationManager.getApplication().invokeLater(() -> compiledTextConsumer.clearCompiledText("Exception compiling " + name + ": " + e.getMessage()));
                }
            }
        };
        ProgressManager.getInstance().runProcessWithProgressAsynchronously(task, new BackgroundableProcessIndicator(task));
    }
}