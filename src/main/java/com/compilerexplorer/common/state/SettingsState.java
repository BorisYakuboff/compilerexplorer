package com.compilerexplorer.common.state;

import com.intellij.util.xmlb.annotations.Property;
import org.jetbrains.annotations.NotNull;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.*;
import java.util.stream.Collectors;

@XmlAccessorType(XmlAccessType.PROPERTY)
public class SettingsState {
    @NotNull
    private static final String DEFAULT_URL = "http://localhost:10240";

    @NotNull
    @Property
    private String url = DEFAULT_URL;
    @Property
    private boolean connected = false;
    @NotNull
    @Property
    private String lastConnectionStatus = "";
    @NotNull
    @Property
    private List<RemoteCompilerInfo> remoteCompilers = new ArrayList<>();
    @NotNull
    @Property
    private Map<RemoteCompilerId, Defines> remoteCompilerDefines = new HashMap<>();
    @NotNull
    @Property
    private Map<LocalCompilerPath, LocalCompilerSettings> localCompilerSettings = new HashMap<>();
    @NotNull
    @Property
    private Filters filters = new Filters();
    @Property
    private boolean allowMinorVersionMismatch = false;
    @NotNull
    @Property
    private Map<LocalCompilerPath, CompilerMatches> compilerMatches = new HashMap<>();

    public SettingsState() {
        // empty
    }

    public SettingsState(@NotNull SettingsState other) {
        copyFrom(other);
    }

    @NotNull
    public String getUrl() {
        return url;
    }

    public void setUrl(@NotNull String url_) {
        url = url_;
    }

    public boolean getConnected() {
        return connected;
    }

    public void setConnected(boolean connected_) {
        connected = connected_;
    }

    @NotNull
    public String getLastConnectionStatus() {
        return lastConnectionStatus;
    }

    public void setLastConnectionStatus(@NotNull String lastConnectionStatus_) {
        lastConnectionStatus = lastConnectionStatus_;
    }

    @NotNull
    public List<RemoteCompilerInfo> getRemoteCompilers() {
        return remoteCompilers;
    }

    public void setRemoteCompilers(@NotNull List<RemoteCompilerInfo> remoteCompilers_) {
        remoteCompilers = new ArrayList<>();
        for (RemoteCompilerInfo otherInfo : remoteCompilers_) {
            remoteCompilers.add(new RemoteCompilerInfo(otherInfo));
        }
    }

    @NotNull
    public Map<RemoteCompilerId, Defines> getRemoteCompilerDefines() {
        return remoteCompilerDefines;
    }

    public void setRemoteCompilerDefines(@NotNull Map<RemoteCompilerId, Defines> remoteCompilerDefines_) {
        remoteCompilerDefines = new HashMap<>();
        for (Map.Entry<RemoteCompilerId, Defines> otherEntry : remoteCompilerDefines_.entrySet()) {
            remoteCompilerDefines.put(new RemoteCompilerId(otherEntry.getKey()), new Defines(otherEntry.getValue()));
        }
    }

    @NotNull
    public Map<LocalCompilerPath, LocalCompilerSettings> getLocalCompilerSettings() {
        return localCompilerSettings;
    }

    public void setLocalCompilerSettings(@NotNull Map<LocalCompilerPath, LocalCompilerSettings> localCompilers_) {
        localCompilerSettings = new HashMap<>();
        for (Map.Entry<LocalCompilerPath, LocalCompilerSettings> otherEntry : localCompilers_.entrySet()) {
            localCompilerSettings.put(new LocalCompilerPath(otherEntry.getKey()), new LocalCompilerSettings(otherEntry.getValue()));
        }
    }

    @NotNull
    public Filters getFilters() {
        return filters;
    }

    public void setFilters(@NotNull Filters filters_) {
        filters = new Filters(filters_);
    }

    public boolean getAllowMinorVersionMismatch() {
        return allowMinorVersionMismatch;
    }

    public void setAllowMinorVersionMismatch(boolean allowMinorVersionMismatch_) {
        allowMinorVersionMismatch = allowMinorVersionMismatch_;
    }

    @NotNull
    public Map<LocalCompilerPath, CompilerMatches> getCompilerMatches() {
        return compilerMatches;
    }

    public void setCompilerMatches(@NotNull Map<LocalCompilerPath, CompilerMatches> compilerMatches_) {
        compilerMatches = new HashMap<>();
        for (Map.Entry<LocalCompilerPath, CompilerMatches> otherEntry : compilerMatches_.entrySet()) {
            compilerMatches.put(new LocalCompilerPath(otherEntry.getKey()), new CompilerMatches(otherEntry.getValue()));
        }
    }

    public boolean isConnectionCleared() {
        return !getConnected() && getLastConnectionStatus().isEmpty();
    }

    public void clearConnection() {
        setConnected(false);
        setLastConnectionStatus("");
        setRemoteCompilers(new ArrayList<>());
        setRemoteCompilerDefines(new HashMap<>());
        setCompilerMatches(new HashMap<>());
    }

    public void clearLocalCompilers() {
        setLocalCompilerSettings(new HashMap<>());
        setCompilerMatches(new HashMap<>());
    }

    public void copyFrom(@NotNull SettingsState other) {
        setUrl(other.getUrl());
        setConnected(other.getConnected());
        setLastConnectionStatus(other.getLastConnectionStatus());
        setRemoteCompilers(other.getRemoteCompilers());
        setRemoteCompilerDefines(other.getRemoteCompilerDefines());
        setLocalCompilerSettings(other.getLocalCompilerSettings());
        setFilters(other.getFilters());
        setAllowMinorVersionMismatch(other.getAllowMinorVersionMismatch());
        setCompilerMatches(other.getCompilerMatches());
    }

    @Override
    public int hashCode() {
        return getUrl().hashCode()
             + (getConnected() ? 1 : 0)
             + getLastConnectionStatus().hashCode()
             + getRemoteCompilers().hashCode()
             + getRemoteCompilerDefines().hashCode()
             + getLocalCompilerSettings().hashCode()
             + getFilters().hashCode()
             + (getAllowMinorVersionMismatch() ? 1 : 0)
             + getCompilerMatches().hashCode()
        ;
    }

    @Override
    public boolean equals(@NotNull Object obj) {
        if (!(obj instanceof SettingsState)) {
            return false;
        }
        SettingsState other = (SettingsState)obj;
        return getUrl().equals(other.getUrl())
            && getConnected() == other.getConnected()
            && getLastConnectionStatus().equals(other.getLastConnectionStatus())
            && getRemoteCompilers().equals(other.getRemoteCompilers())
            && getRemoteCompilerDefines().equals(other.getRemoteCompilerDefines())
            && getLocalCompilerSettings().equals(other.getLocalCompilerSettings())
            && getFilters().equals(other.getFilters())
            && getAllowMinorVersionMismatch() == other.getAllowMinorVersionMismatch()
            && getCompilerMatches().equals(other.getCompilerMatches())
        ;
    }
}